package com.gmd.faq.server.main.engine.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.engine.rag.embedding.OpenAIEmbeddingProvider;
import com.gmd.faq.server.main.knowledge.service.KnowledgeModelService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeModelService models = mock(KnowledgeModelService.class);
    private final KnowledgeBaseConfig config = new KnowledgeBaseConfig();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private EmbeddingService service;
    private ObjectNode connection;
    private volatile String response;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        response = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";
        server.createContext("/v1/embeddings", exchange -> {
            bodies.add(json.readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        connection = json.createObjectNode().put("embeddingModelId", "12").put("providerType", "openai")
                .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings")
                .put("embeddingModel", "platform-embedding").put("apiKey", "current-model-key").put("dimension", 2);
        when(models.embeddingConnection(config)).thenReturn(connection);
        service = new EmbeddingService(List.of(new OpenAIEmbeddingProvider()), models);
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void callsPlatformEndpointWithItsModelAndCredentialsForEveryBatch() {
        connection.put("batchSize", 1).put("sendDimensions", true);
        var vectors = service.embed(List.of("first", "second"), config);
        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{1, 2}, vectors.getFirst());
        assertEquals(List.of("Bearer current-model-key", "Bearer current-model-key"), authorizations);
        assertEquals("platform-embedding", bodies.getFirst().path("model").asText());
        assertEquals("first", bodies.getFirst().path("input").get(0).asText());
        assertEquals("second", bodies.get(1).path("input").get(0).asText());
        assertEquals(2, bodies.getFirst().path("dimensions").asInt());
        assertFalse(bodies.getFirst().has("dimension"));
        verify(models).embeddingConnection(config);
    }

    @Test
    void preservesInputOrderAndOmitsUnsupportedDimensionsParameter() {
        response = "{\"data\":[{\"index\":1,\"embedding\":[3,4]},{\"index\":0,\"embedding\":[1,2]}]}";
        var vectors = service.embed(List.of("first", "second"), config);
        assertArrayEquals(new float[]{1, 2}, vectors.getFirst());
        assertArrayEquals(new float[]{3, 4}, vectors.get(1));
        assertFalse(bodies.getFirst().has("dimensions"));
    }

    @Test
    void rejectsWrongCountDimensionAndDuplicateIndexes() {
        assertThrows(RuntimeException.class, () -> service.embed(List.of("first", "second"), config));
        response = "{\"data\":[{\"index\":0,\"embedding\":[1]}]}";
        assertThrows(RuntimeException.class, () -> service.embed(List.of("first"), config));
        response = "{\"data\":[{\"index\":0,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[3,4]}]}";
        assertThrows(RuntimeException.class, () -> service.embed(List.of("first", "second"), config));
    }

    @Test
    void rejectsInvalidBatchWithoutEnteringRequestLoop() {
        connection.put("batchSize", 0);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(IllegalArgumentException.class, () -> service.embed(List.of("first"), config)));
        assertTrue(bodies.isEmpty());
    }
}
