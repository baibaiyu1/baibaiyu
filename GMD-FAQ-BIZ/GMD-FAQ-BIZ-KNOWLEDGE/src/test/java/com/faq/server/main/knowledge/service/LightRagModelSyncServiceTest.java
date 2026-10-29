package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LightRagModelSyncServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeModelService models = mock(KnowledgeModelService.class);
    private LightRagModelSyncService service;
    private final KnowledgeBaseConfig config = new KnowledgeBaseConfig();
    private HttpServer server;
    private volatile int status = 200;
    private volatile String response = "{\"status\":\"success\",\"restart_required\":false,\"rebuild_required\":false}";
    private volatile String method;
    private volatile JsonNode body;
    private volatile String key;
    private volatile String authorization;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rag/models/config", exchange -> {
            method = exchange.getRequestMethod();
            body = json.readTree(exchange.getRequestBody());
            key = exchange.getRequestHeaders().getFirst("X-API-Key");
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service = new LightRagModelSyncService(models, new LightRagConnectionResolver(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/rag", "builtin-key"));
        config.setConnectionConfig(json.createObjectNode()
                .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/rag/").put("apiKey", "server-key"));
        when(models.lightRagPayload(config)).thenReturn(json.createObjectNode().put("owner", "9:42")
                .set("embedding", json.createObjectNode().put("model", "platform-vector").put("api_key", "model-key")));
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void sendsResolvedPayloadAndOnlyLightRagCredentialInHeaders() {
        service.ensureReady(config);
        assertEquals("PUT", method);
        assertEquals("server-key", key);
        assertNull(authorization);
        assertEquals("9:42", body.path("owner").asText());
        assertEquals("model-key", body.path("embedding").path("api_key").asText());
    }

    @Test
    void refusesQueriesUntilRestartAndReportsOldServerAndOwnershipConflicts() {
        response = "{\"status\":\"success\",\"restart_required\":true,\"rebuild_required\":true}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.ensureReady(config)).getMessage().contains("重启"));
        status = 404;
        response = "sensitive-provider-error";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.sync(config)).getMessage().contains("更新"));
        status = 409;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.sync(config)).getMessage().contains("冲突"));
        status = 500;
        assertFalse(assertThrows(IllegalArgumentException.class, () -> service.sync(config)).getMessage().contains(response));
    }

    @Test
    void legacyConfigurationDoesNotCallUnsupportedSyncEndpoint() {
        when(models.lightRagPayload(config)).thenReturn(null);
        service.ensureReady(config);
        assertNull(method);
    }

    @Test
    void initialBindingNeedsRestartWithoutRequestingVectorRebuild() {
        response = "{\"status\":\"success\",\"restart_required\":true,\"rebuild_required\":false}";
        String message = assertThrows(IllegalArgumentException.class, () -> service.ensureReady(config)).getMessage();
        assertTrue(message.contains("重启"));
        assertFalse(message.contains("重建"));
    }

    @Test
    void builtinUsesDeploymentConnectionWithoutSavingOrForwardingClientCredentials() {
        config.setConnectionConfig(json.createObjectNode().put("mode", "BUILTIN")
                .put("baseUrl", "https://untrusted.test").put("apiKey", "client-key"));
        service.ensureReady(config);
        assertEquals("builtin-key", key);
        assertNull(authorization);
        assertEquals("9:42", body.path("owner").asText());
        assertFalse(config.getConnectionConfig().toString().contains("builtin-key"));
    }
}
