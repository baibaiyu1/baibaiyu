package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.entity.ModelProvider;
import com.gmd.faq.server.main.common.enums.AuthType;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeModelServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final KnowledgeModelService service = new KnowledgeModelService(models, json);
    private ModelConfig embedding;
    private ModelProvider provider;

    @BeforeEach
    void setup() {
        TenantUtils.setCurrentTenant(9L, "test");
        provider = new ModelProvider();
        provider.setId(5L);
        provider.setTenantId(9L);
        provider.setEnabled(true);
        provider.setType(ModelProviderType.OPEN_AI);
        provider.setName("平台模型供应商");
        provider.setBaseUrl("https://models.test/v1/");
        provider.setAuthType(AuthType.CONFIG);
        provider.setApiKey("provider-secret");
        embedding = register(12L, "EMBEDDING");
        embedding.setExtendConfig(json.createObjectNode().put("embeddingDimension", 1024).put("embeddingSendDimensions", true));
    }

    private ModelConfig register(Long id, String usage) {
        var config = new ModelConfig();
        config.setId(id);
        config.setTenantId(9L);
        config.setEnabled(true);
        config.setProviderId(5L);
        config.setName("模型" + id);
        config.setModelId("model-" + id);
        config.setModelType(json.createArrayNode().add(usage));
        when(models.getById(id)).thenReturn(config);
        when(models.getModelWrapperById(id)).thenReturn(ModelWrapper.builder().config(config).provider(provider).build());
        return config;
    }

    @AfterEach
    void cleanup() { TenantUtils.clear(); }

    private KnowledgeBaseConfig knowledge(KbType type) {
        var config = new KnowledgeBaseConfig();
        config.setId(42L);
        config.setKbType(type);
        config.setConnectionConfig(json.createObjectNode().put("embeddingModelId", "12"));
        return config;
    }

    @Test
    void storesOnlyReferenceAndResolvesCurrentCredentialsAtExecution() {
        var config = knowledge(KbType.LOCAL);
        service.validateReferences(config);
        assertFalse(config.getConnectionConfig().has("apiKey"));
        assertEquals(1024, config.getConnectionConfig().path("dimension").asInt());
        assertEquals("https://models.test/v1/embeddings", service.embeddingConnection(config).path("baseUrl").asText());
        assertEquals("provider-secret", service.embeddingConnection(config).path("apiKey").asText());
        provider.setApiKey("rotated-secret");
        assertEquals("rotated-secret", service.embeddingConnection(config).path("apiKey").asText());
    }

    @Test
    void rejectsCrossTenantAndMissingContextEvenWhenMapperReturnsForeignRecord() {
        embedding.setTenantId(10L);
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(knowledge(KbType.LOCAL)));
        embedding.setTenantId(9L);
        provider.setTenantId(10L);
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(knowledge(KbType.LOCAL)));
        TenantUtils.clear();
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(knowledge(KbType.LOCAL)));
    }

    @Test
    void rechecksDisabledModelsAtExecutionAndRejectsChangedEmbeddingIdentity() {
        var config = knowledge(KbType.LOCAL);
        service.validateReferences(config);
        embedding.setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> service.embeddingConnection(config));
        embedding.setEnabled(true);
        embedding.setModelId("different-model-same-dimension");
        assertThrows(IllegalArgumentException.class, () -> service.embeddingConnection(config));
        assertEquals(1024, config.getConnectionConfig().path("dimension").asInt());
    }

    @Test
    void validatesStorageDimensionsAndRejectsInvalidBatchSettings() {
        embedding.setExtendConfig(json.createObjectNode().put("embeddingDimension", 1536));
        service.validateReferences(knowledge(KbType.LOCAL));
        embedding.setExtendConfig(json.createObjectNode().put("embeddingDimension", 3072));
        service.validateReferences(knowledge(KbType.LOCAL));
        embedding.setExtendConfig(json.createObjectNode().put("embeddingDimension", 4096));
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(knowledge(KbType.LOCAL)));
        service.validateReferences(knowledge(KbType.LIGHTRAG));
        embedding.setExtendConfig(json.createObjectNode().put("embeddingDimension", 1024));
        var config = knowledge(KbType.LOCAL);
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.getConnectionConfig()).put("batchSize", 0);
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(config));
    }

    @Test
    void rejectsWrongUsageProtocolAndMissingDimension() {
        var config = knowledge(KbType.LIGHTRAG);
        embedding.setModelType(json.createArrayNode().add("CHAT"));
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(config));
        embedding.setModelType(json.createArrayNode().add("EMBEDDING"));
        embedding.setApiType(ModelApiType.RESPONSES);
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(config));
        embedding.setApiType(ModelApiType.CHAT_COMPLETIONS);
        embedding.setExtendConfig(null);
        assertThrows(IllegalArgumentException.class, () -> service.validateReferences(config));
    }

    @Test
    void selectsEachLightRagRoleAndDoesNotAssignTextOnlyModelToVision() {
        register(13L, "CHAT");
        register(14L, "IMAGE");
        register(15L, "RERANK");
        var config = knowledge(KbType.LIGHTRAG);
        var connection = (com.fasterxml.jackson.databind.node.ObjectNode) config.getConnectionConfig();
        connection.put("llmModelId", "13");
        var payload = service.lightRagPayload(config);
        assertEquals("model-13", payload.path("roles").path("extract").path("model").asText());
        assertFalse(payload.path("roles").has("vlm"));
        connection.put("vlmModelId", "14").put("rerankModelId", "15");
        payload = service.lightRagPayload(config);
        assertEquals("model-14", payload.path("roles").path("vlm").path("model").asText());
        assertEquals("cohere", payload.path("rerank").path("binding").asText());
        assertEquals("https://models.test/v1/rerank", payload.path("rerank").path("host").asText());
        assertEquals("9:42", payload.path("owner").asText());
    }

    @Test
    void optionsDoNotExposeCredentialsAndSkipDisabledOrIncompatibleModels() throws Exception {
        var unsupported = register(13L, "CHAT");
        unsupported.setApiType(ModelApiType.RESPONSES);
        when(models.list()).thenReturn(List.of(embedding, unsupported));
        var options = service.options();
        assertEquals(1, options.size());
        assertEquals(List.of("EMBEDDING"), options.getFirst().usages());
        assertFalse(json.writeValueAsString(options).contains("secret"));
        assertFalse(json.writeValueAsString(options).contains("apiKey"));
    }

    @Test
    void preservesLegacyConfigurationAndSendsExplicitResetForManagedGraph() {
        var config = new KnowledgeBaseConfig();
        config.setId(42L);
        config.setKbType(KbType.LOCAL);
        config.setConnectionConfig(json.createObjectNode().put("providerType", "ollama"));
        assertSame(config.getConnectionConfig(), service.embeddingConnection(config));
        assertNull(service.lightRagPayload(config));
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.getConnectionConfig()).put("platformModelsManaged", true);
        assertEquals(0, service.lightRagPayload(config).path("roles").size());
    }
}
