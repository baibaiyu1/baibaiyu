package com.gmd.faq.server.main.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.*;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.model.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentReasoningPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final ModelProviderService providers = mock(ModelProviderService.class);
    private final AgentReasoningPolicy policy = new AgentReasoningPolicy(models, providers);
    private Object previousMapper;
    @BeforeEach void setup() {
        previousMapper = ReflectionTestUtils.getField(JsonUtils.class, "objectMapper");
        ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", json);
    }
    @AfterEach void cleanup() { ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", previousMapper); }
    private ModelWrapper model(String code, ModelProviderType type) {
        var config = new ModelConfig(); config.setModelId(code); config.setMaxTokens(8192);
        var provider = new ModelProvider(); provider.setType(type);
        return ModelWrapper.builder().config(config).provider(provider).build();
    }
    private AgentDefinitionVO agent(String settings) throws Exception {
        var agent = new AgentDefinitionVO(); agent.setAgentType(AgentType.CUSTOM); agent.setModelConfigId(1L);
        agent.setModelParamsOverride(json.readTree(settings)); return agent;
    }
    @Test void validatesIndependentReasoningAgainstFallbackButLeavesCompactionIndependent() throws Exception {
        when(models.getModelWrapperById(1L)).thenReturn(model("deepseek-flash", ModelProviderType.OPEN_AI));
        when(models.getModelWrapperById(2L)).thenReturn(model("gpt-5.2", ModelProviderType.OPEN_AI));
        var agent = agent("{\"reasoning\":{\"mode\":\"ENABLED\",\"effort\":\"max\"}}");
        assertDoesNotThrow(() -> policy.validate(agent));
        agent.setModelResilienceConfig(json.readTree("{\"fallbackModelId\":\"2\"}"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(agent));
        agent.setModelResilienceConfig(json.readTree("{\"summaryModelId\":\"2\"}"));
        assertDoesNotThrow(() -> policy.validate(agent));
    }
    @Test void inheritedBudgetCannotBeBypassedWithSamplingOverridesAndRawConflictsAreRejected() throws Exception {
        var primary = model("claude-sonnet-4-5", ModelProviderType.ANTHROPIC);
        primary.getConfig().setExtendConfig(json.readTree("{\"reasoning\":{\"mode\":\"ENABLED\",\"budgetTokens\":2048}}"));
        when(models.getModelWrapperById(1L)).thenReturn(primary);
        var agent = agent("{\"maxTokens\":1024}");
        assertThrows(IllegalArgumentException.class, () -> policy.validate(agent));
        agent.setModelParamsOverride(json.readTree("{\"reasoning\":{\"mode\":\"DISABLED\"},\"extendConfig\":{\"bodyParams\":{\"thinking\":{\"type\":\"enabled\"}}}}"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(agent));
    }
    @Test void legacyRawOverrideIsReturnedAsAnIndependentCopyAndSavedWithValidation() throws Exception {
        var primary = model("deepseek-flash", ModelProviderType.OPEN_AI); primary.getConfig().setProviderId(2L);
        when(models.getById(1L)).thenReturn(primary.getConfig());
        when(providers.getById(2L)).thenReturn(primary.getProvider());
        when(models.getModelWrapperById(1L)).thenReturn(primary);
        var agent = agent("{\"temperature\":0.3,\"extendConfig\":{\"bodyParams\":{\"reasoning_effort\":\"xhigh\",\"custom\":1}}}");
        var original = agent.getModelParamsOverride();
        assertEquals("high", policy.resolve(agent).effort());
        assertTrue(original.path("extendConfig").path("bodyParams").has("reasoning_effort"));
        assertEquals("high", agent.getModelParamsOverride().path("reasoning").path("effort").asText());
        assertEquals(1, agent.getModelParamsOverride().path("extendConfig").path("bodyParams").path("custom").asInt());
        assertDoesNotThrow(() -> policy.validate(agent));
    }
}
