package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightRagConnectionResolverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LightRagConnectionResolver resolver = new LightRagConnectionResolver("http://lightrag:9621/", "deployment-secret");

    @Test
    void defaultsToBuiltinWithoutPersistingDeploymentCredentials() {
        var config = new KnowledgeBaseConfig();
        var connection = resolver.resolve(config);
        assertTrue(connection.builtin());
        assertEquals("http://lightrag:9621", connection.baseUrl());
        assertEquals("deployment-secret", connection.apiKey());
        assertNull(config.getConnectionConfig());
        assertFalse(connection.toString().contains("deployment-secret"));
    }

    @Test
    void builtinIgnoresClientCredentialsAndPreservesModelReferences() {
        var config = new KnowledgeBaseConfig();
        config.setConnectionConfig(json.createObjectNode().put("mode", "BUILTIN")
                .put("baseUrl", "https://untrusted.test").put("apiKey", "client-key").put("llmModelId", "42"));
        var normalized = LightRagConnectionResolver.normalize(config.getConnectionConfig());
        assertFalse(normalized.has("baseUrl"));
        assertFalse(normalized.has("apiKey"));
        assertEquals("42", normalized.path("llmModelId").asText());
        assertEquals("deployment-secret", resolver.resolve(config).apiKey());
        assertEquals("http://lightrag:9621", resolver.resolve(config).baseUrl());
    }

    @Test
    void preservesExistingExternalEndpointsAndTheirOwnCredentials() {
        var config = new KnowledgeBaseConfig();
        config.setConnectionConfig(json.createObjectNode().put("baseUrl", " https://external.test/prefix/ ")
                .put("apiKey", " remote-key "));
        var connection = resolver.resolve(config);
        assertFalse(connection.builtin());
        assertEquals("https://external.test/prefix", connection.baseUrl());
        assertEquals("remote-key", connection.apiKey());
        assertEquals("EXTERNAL", LightRagConnectionResolver.normalize(config.getConnectionConfig()).path("mode").asText());
    }

    @Test
    void refusesInvalidExternalConfigurationAndUnconfiguredBuiltinService() {
        for (String value : List.of("", "file:///etc/passwd", "https://user:password@test", "https://test?q=1", "https://test/#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> LightRagConnectionResolver.normalize(
                    json.createObjectNode().put("mode", "EXTERNAL").put("baseUrl", value)));
        }
        assertThrows(IllegalArgumentException.class, () -> LightRagConnectionResolver.normalize(json.createObjectNode().put("mode", "other")));
        assertThrows(IllegalArgumentException.class, () -> new LightRagConnectionResolver("http://lightrag:9621", "")
                .resolve(new KnowledgeBaseConfig()));
    }
}
