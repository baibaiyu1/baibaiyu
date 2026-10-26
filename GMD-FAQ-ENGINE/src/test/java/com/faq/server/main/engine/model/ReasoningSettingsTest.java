package com.gmd.faq.server.main.engine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.*;
import com.gmd.faq.server.main.common.model.*;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.wrapper.*;
import com.gmd.faq.server.main.engine.telemetry.AgentTelemetry;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import io.agentscope.core.model.Model;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReasoningSettingsTest {
    private final ObjectMapper json = new ObjectMapper();
    private Object previousMapper;
    @BeforeEach void setup() {
        previousMapper = ReflectionTestUtils.getField(JsonUtils.class, "objectMapper");
        ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", json);
    }
    @AfterEach void cleanup() { ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", previousMapper); }
    private JsonNode node(String value) throws Exception { return json.readTree(value); }
    private ModelWrapper model(String code, String settings) throws Exception {
        ModelConfig config = new ModelConfig(); config.setModelId(code); config.setApiType(ModelApiType.RESPONSES);
        config.setThinking(false); config.setMaxTokens(8192); config.setExtendConfig(node(settings));
        ModelProvider provider = new ModelProvider(); provider.setType(ModelProviderType.OPEN_AI); provider.setBaseUrl("https://proxy.test/v1");
        provider.setAuthType(AuthType.CONFIG); provider.setApiKey("fixture");
        return ModelWrapper.builder().config(config).provider(provider).build();
    }

    @Test void independentSnapshotSurvivesModelChangesWhileInheritedSettingsFollowThem() throws Exception {
        var model = model("deepseek-flash", "{\"reasoning\":{\"mode\":\"ENABLED\",\"effort\":\"high\",\"showThinking\":true}}");
        var snapshot = node("{\"reasoning\":{\"mode\":\"ENABLED\",\"effort\":\"low\",\"showThinking\":false}}");
        model.getConfig().setExtendConfig(node("{\"reasoning\":{\"mode\":\"DISABLED\",\"showThinking\":true}}"));
        var independent = ReasoningConfigSupport.effective(model.getConfig(), model.getProvider(), snapshot);
        assertEquals("ENABLED", independent.mode()); assertEquals("low", independent.effort()); assertFalse(independent.showThinking());
        assertEquals("DISABLED", ReasoningConfigSupport.effective(model.getConfig(), model.getProvider(), null).mode());
    }

    @Test void legacyIgnoredSwitchAndAliasesAreReadWithoutChangingStoredConfiguration() throws Exception {
        var model = model("opaque-name", "{\"bodyParams\":{\"reasoning.effort\":\"xhigh\"}}");
        model.getProvider().setBaseUrl("https://api.deepseek.com");
        assertEquals("high", ReasoningConfigSupport.modelSettings(model.getConfig(), model.getProvider()).effort());
        var legacy = node("{\"temperature\":0.2,\"extendConfig\":{\"headers\":{\"X-Test\":\"keep\"},\"bodyParams\":{\"reasoning.effort\":\"max\",\"custom\":3}}}");
        var normalized = ReasoningConfigSupport.normalizeOverride(model.getConfig(), model.getProvider(), legacy);
        assertEquals("max", normalized.path("reasoning").path("effort").asText());
        assertFalse(normalized.path("extendConfig").path("bodyParams").has("reasoning.effort"));
        assertEquals(3, normalized.path("extendConfig").path("bodyParams").path("custom").asInt());
        assertTrue(legacy.path("extendConfig").path("bodyParams").has("reasoning.effort"));
        model.getConfig().setExtendConfig(null);
        assertEquals("DEFAULT", ReasoningConfigSupport.modelSettings(model.getConfig(), ModelProviderType.OPEN_AI).mode());
        assertEquals("DISABLED", ReasoningConfigSupport.modelSettings(model.getConfig(), ModelProviderType.DASH_SCOPE).mode());
    }

    @Test void primaryAndFallbackShareReasoningIntentAndCompactionKeepsItsOwnDefaults() throws Exception {
        var models = mock(ModelConfigService.class); var transport = mock(IChatModel.class); var telemetry = mock(AgentTelemetry.class);
        when(transport.getProvider()).thenReturn(ModelProviderType.OPEN_AI);
        List<ModelConfigWrapper> configs = new ArrayList<>();
        when(transport.getModel(any())).thenAnswer(call -> { configs.add(call.getArgument(0)); return mock(Model.class); });
        when(models.getModelWrapperById(1L)).thenReturn(model("deepseek-flash", "{\"reasoning\":{\"mode\":\"DISABLED\"}}"));
        when(models.getModelWrapperById(2L)).thenReturn(model("deepseek-pro", "{\"reasoning\":{\"mode\":\"DISABLED\"}}"));
        when(models.getModelWrapperById(3L)).thenReturn(model("deepseek-flash", "{\"reasoning\":{\"mode\":\"ENABLED\",\"effort\":\"low\"}}"));
        var factory = new ChatModelFactory(List.of(transport), models, telemetry);
        var agent = new AgentDefinition(); agent.setId(10L); agent.setModelConfigId(1L);
        agent.setModelParamsOverride(node("{\"extendConfig\":{\"bodyParams\":{\"reasoning.effort\":\"max\"}}}"));
        factory.getModel(agent); factory.getAuxiliaryModel(agent, 2L, "FALLBACK"); factory.getAuxiliaryModel(agent, 3L, "COMPACTION");
        assertEquals(List.of("max", "max", "low"), configs.stream().map(c -> c.getResolvedReasoning().effort()).toList());
        assertTrue(agent.getModelParamsOverride().path("extendConfig").path("bodyParams").has("reasoning.effort"));
    }

    @Test void modeAndCapabilitiesRejectConflictsInvalidTypesAndImpossibleBudgets() throws Exception {
        for (String invalid : List.of("{\"mode\":\"DISABLED\",\"effort\":\"max\"}", "{\"mode\":false}",
                "{\"mode\":\"ENABLED\",\"budgetTokens\":1.5}", "{\"mode\":\"ENABLED\",\"effort\":3}", "{\"showThinking\":\"false\"}"))
            assertThrows(IllegalArgumentException.class, () -> ReasoningConfigSupport.parse(node(invalid)));
        var settings = node("{\"mode\":\"DISABLED\"}");
        for (String extend : List.of("{\"bodyParams\":{\"thinking\":{\"type\":\"enabled\"}}}", "{\"queryParams\":{\"reasoning_effort\":\"max\"}}"))
            assertThrows(IllegalArgumentException.class, () -> ReasoningConfigSupport.rejectConflicts(node(extend), settings));
        var fixed = ReasoningConfigSupport.capabilities(ModelProviderType.OPEN_AI, null, "deepseek-reasoner", ModelApiType.CHAT_COMPLETIONS, "AUTO");
        assertThrows(IllegalArgumentException.class, () -> ReasoningConfigSupport.validate(ReasoningConfigSupport.parse(settings), fixed, 8192));
        var anthropic = ReasoningConfigSupport.capabilities(ModelProviderType.ANTHROPIC, null, "claude-sonnet-4-5", null, "AUTO");
        assertThrows(IllegalArgumentException.class, () -> ReasoningConfigSupport.validate(new ReasoningSettings("ENABLED", null, 1024, true), anthropic, 1024));
    }

    @Test void nativeProviderOptionsUseTheirOwnSwitchAndBudgetAndDefaultDoesNotForceOff() throws Exception {
        for (var provider : List.of(ModelProviderType.DASH_SCOPE, ModelProviderType.ANTHROPIC, ModelProviderType.GEMINI, ModelProviderType.OLLAMA)) {
            var config = ModelConfigWrapper.builder().provider(provider).modelCode("native-model").maxTokens(8192)
                    .reasoning(node("{\"mode\":\"ENABLED\",\"budgetTokens\":2048}")).build();
            if (provider == ModelProviderType.OLLAMA) config.setReasoning(node("{\"mode\":\"ENABLED\"}"));
            var options = GenerateOptionsHelper.create(config);
            switch (provider) {
                case DASH_SCOPE -> { assertEquals(true, config.getThinking()); assertEquals(2048, options.getAdditionalBodyParams().get("thinking_budget")); }
                case ANTHROPIC -> assertEquals(Map.of("type", "enabled", "budget_tokens", 2048), options.getAdditionalBodyParams().get("thinking"));
                case GEMINI -> assertEquals(2048, options.getThinkingBudget());
                case OLLAMA -> assertEquals(true, options.getAdditionalBodyParams().get("think"));
                default -> fail();
            }
            config.setReasoning(node("{\"mode\":\"DEFAULT\"}")); GenerateOptionsHelper.create(config);
            assertNull(config.getThinking()); assertNull(config.getReasoningBudget()); assertTrue(config.getBodyParams().isEmpty());
        }
    }
}
