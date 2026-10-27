package com.gmd.faq.server.main.engine.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.engine.knowledge.impl.LightRagIKnowledge;
import com.gmd.faq.server.main.engine.knowledge.lightrag.LightRagKnowledge;
import com.gmd.faq.server.main.common.wrapper.KnowledgeWrapper;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LightRagKnowledgeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String RESULT = """
            {"status":"success","data":{
              "entities":[{"entity_name":"光明顶","description":"智能体平台"}],
              "relationships":[{"src_id":"光明顶","tgt_id":"LightRAG","description":"使用知识图谱"}],
              "chunks":[
                {"chunk_id":"c1","content":"知识库可以绑定到智能体。","reference_id":"1","file_path":"handbook.md"},
                {"chunk_id":"c2","content":"第二段。","reference_id":"1"}],
              "references":[{"reference_id":"1","file_path":"handbook.md"}]}}
            """;

    private HttpServer server;
    private ExecutorService executor;
    private KnowledgeBaseConfig config;
    private volatile int status;
    private volatile String body;
    private volatile long delayMs;
    private final AtomicInteger transientFailures = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        status = 200;
        body = RESULT;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/site/query/data", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestURI().getPath(), MAPPER.readTree(requestBody),
                    exchange.getRequestHeaders().getFirst("X-API-Key"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("LIGHTRAG-WORKSPACE")));
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                int responseStatus = transientFailures.getAndUpdate(count -> Math.max(0, count - 1)) > 0 ? 503 : status;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        config = new KnowledgeBaseConfig();
        config.setId(42L);
        config.setEnabled(true);
        config.setKbType(com.gmd.faq.server.main.common.enums.KbType.LIGHTRAG);
        config.setConnectionConfig(MAPPER.createObjectNode()
                .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/site/")
                .put("apiKey", "test-key"));
        config.setRetrievalConfig(MAPPER.createObjectNode().put("topK", 2).put("graphTopK", 31)
                .put("mode", "hybrid").put("maxEntityTokens", 1000).put("maxRelationTokens", 2000)
                .put("maxTotalTokens", 7000).put("enableRerank", false));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void retrievesGraphAndSourcesWithoutFabricatingScoresOrForwardingIdentity() {
        var configs = org.mockito.Mockito.mock(com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService.class);
        org.mockito.Mockito.when(configs.getById(config.getId())).thenReturn(config);
        var knowledge = new LightRagIKnowledge(MAPPER,
                org.mockito.Mockito.mock(com.gmd.faq.server.main.knowledge.service.LightRagModelSyncService.class), configs).build(config);
        var docs = knowledge.retrieve("  如何使用知识库？  ", RetrieveConfig.builder().limit(1).scoreThreshold(0.99).build()).block(WAIT);
        assertNotNull(docs);
        assertEquals(2, docs.size(), "图谱上下文不能被片段上限截掉");
        assertTrue(docs.getFirst().getMetadata().getContentText().contains("使用知识图谱"));
        assertTrue(docs.getFirst().getMetadata().getContentText().contains("光明顶"));
        assertTrue(docs.get(1).getMetadata().getContentText().contains("handbook.md"));
        assertEquals("c1", docs.get(1).getMetadata().getChunkId());
        assertEquals("chunk", docs.get(1).getPayloadValue("contentType"));
        assertEquals("1", docs.get(1).getPayloadValue("reference_id"));
        assertTrue(docs.stream().allMatch(doc -> doc.getScore() == null));
        Request request = requests.getFirst();
        assertEquals("/site/query/data", request.path());
        assertEquals("test-key", request.apiKey());
        assertNull(request.authorization());
        assertNull(request.workspace());
        assertEquals("如何使用知识库？", request.body().path("query").asText());
        assertEquals("hybrid", request.body().path("mode").asText());
        assertEquals(1, request.body().path("chunk_top_k").asInt());
        assertEquals(31, request.body().path("top_k").asInt());
        assertEquals(1000, request.body().path("max_entity_tokens").asInt());
        assertEquals(2000, request.body().path("max_relation_tokens").asInt());
        assertEquals(7000, request.body().path("max_total_tokens").asInt());
        assertFalse(request.body().path("enable_rerank").asBoolean());
        assertTrue(request.body().path("include_images").asBoolean());
        assertFalse(request.body().has("score_threshold"));
        assertFalse(request.body().has("conversation_history"));
    }

    @Test
    void preservesImagesAndKnowledgeIdentityFromTheSameRetrievalRequest() {
        config.setName("设备手册");
        body = RESULT.replace("\"entities\":", "\"images\":[{\"id\":\"im-1\",\"mime_type\":\"image/png\",\"data\":\"aGVsbG8=\",\"caption\":\"设备图\"}],\"entities\":");
        var docs = new LightRagKnowledge(config, MAPPER).retrieve("设备图", null).block(WAIT);
        assertEquals(1, requests.size(), "图片和引用不能额外调用 /query 或重复检索");
        assertEquals("42", docs.getFirst().getPayloadValue("knowledgeId"));
        assertEquals("设备手册", docs.getFirst().getPayloadValue("knowledgeName"));
        assertEquals(1, ((List<?>) docs.getFirst().getPayloadValue("images")).size());
    }

    @Test
    void configuredImageBudgetAboveThreeReachesRemoteAndCapsLocalResponse() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.getRetrievalConfig()).put("maxImages", 5);
        var images = MAPPER.createArrayNode();
        for (int i = 0; i < 8; i++) images.addObject().put("id", "im-" + i).put("caption", "设备图" + i);
        body = RESULT.replace("\"entities\":", "\"images\":" + images + ",\"entities\":");
        var docs = new LightRagKnowledge(config, MAPPER).retrieve("设备图", null).block(WAIT);
        assertEquals(5, requests.getFirst().body().path("max_images").asInt());
        assertEquals(5, ((List<?>) docs.getFirst().getPayloadValue("images")).size());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest @ValueSource(strings = {"naive", "mix", "local", "global", "hybrid"})
    void agentModeAndBudgetsReachOneNativeRequest(String mode) throws Exception {
        config.setKbType(KbType.LIGHTRAG);
        var strategy = MAPPER.createObjectNode().put("topK", 6).put("includeImages", false).put("maxRetries", 0);
        strategy.putObject("lightRag").put("mode", mode).put("graphTopK", 20).put("maxEntityTokens", 1500)
                .put("maxRelationTokens", 2000).put("maxTotalTokens", 6000).put("enableRerank", false);
        var configured = KnowledgeRetrievalSettings.parse(strategy).apply(config);
        new LightRagKnowledge(configured, MAPPER).retrieve("系统架构", RetrieveConfig.builder().limit(6).build()).block(WAIT);
        assertEquals(1, requests.size());
        var request = requests.getFirst().body();
        assertEquals(mode, request.path("mode").asText());
        assertEquals(6, request.path("chunk_top_k").asInt());
        assertEquals(20, request.path("top_k").asInt());
        assertEquals(6000, request.path("max_total_tokens").asInt());
        assertFalse(request.path("enable_rerank").asBoolean());
        assertFalse(request.path("include_images").asBoolean());
        assertEquals("hybrid", config.getRetrievalConfig().path("mode").asText());
    }

    @Test
    void actualSdkToolReceivesGraphAndCitationTextAndUsesConfiguredChunkLimit() {
        var knowledge = new LightRagKnowledge(config, MAPPER);
        String text = new KnowledgeRetrievalTools(knowledge).retrieveKnowledge("知识库怎么用？", 2, null, null);
        assertTrue(text.contains("使用知识图谱"));
        assertTrue(text.contains("handbook.md"));
        assertTrue(text.contains("第二段"));
        assertFalse(text.contains("Score:"));
        assertEquals(2, requests.getFirst().body().path("chunk_top_k").asInt());
    }

    @Test
    void graphSourcesAndProductImagesStayIndependentOfAnUnrelatedVectorHit() throws Exception {
        body = """
                {"status":"success","data":{
                  "entities":[{"entity_name":"GMD9300","description":"嵌入式筒灯，AC220V。",
                    "file_path":"产品画册.pdf<SEP>照明画册.pdf"},
                    {"entity_name":"没有来源的实体","description":"不能借用向量来源"}],
                  "relationships":[{"src_id":"GMD9300","tgt_id":"im-light","description":"产品实物图",
                    "file_path":"产品画册.pdf"}],
                  "chunks":[{"chunk_id":"logo-chunk","reference_id":"1","content":"K9说明书品牌标志",
                    "file_path":"K9说明书.pdf"}],
                  "references":[{"reference_id":"1","file_path":"K9说明书.pdf"}],
                  "images":[{"id":"im-logo","caption":"品牌标志","file_path":"K9说明书.pdf"},
                    {"id":"im-helmet","caption":"安全帽","file_path":"产品画册.pdf"},
                    {"id":"im-cabinet","caption":"柜子","file_path":"产品画册.pdf"},
                    {"id":"im-other-model","caption":"GMD93000产品图","file_path":"产品画册.pdf"},
                    {"id":"im-light","caption":"筒灯产品图","file_path":"产品画册.pdf"}]}}
                """;
        var knowledge = new LightRagKnowledge(config, MAPPER);
        var tool = new KnowledgeRetrievalTool(List.of(KnowledgeWrapper.builder().knowledge(knowledge).build()),
                RetrieveConfig.builder().limit(3).build());
        for (int attempt = 0; attempt < 2; attempt++) {
            var presentation = ToolResultPresentation.from(tool.retrieve("gmd9300", RetrieveConfig.builder().limit(3).build()).block(WAIT));
            var result = MAPPER.readTree(presentation.text());
            assertEquals(1, result.path("images").size());
            assertEquals("im-light", result.path("images").get(0).path("id").asText());
            var sources = result.path("documents").get(0).path("sourceReferences");
            assertEquals(List.of("产品画册.pdf", "照明画册.pdf"),
                    MAPPER.convertValue(sources, new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, String>>>() {})
                            .stream().map(row -> row.get("source")).toList());
            assertTrue(result.path("documents").get(1).path("sourceReferences").isMissingNode(), "无来源实体不能借用 K9 来源");
            assertEquals("产品画册.pdf", result.path("documents").get(2).path("sourceReferences").get(0).path("source").asText());
            assertEquals(3, result.path("documents").size(), "型号来源已明确时，其他文件里无型号信息的向量片段不作为证据");
            assertEquals(2, presentation.citations().size());
            assertTrue(presentation.citations().stream().noneMatch(citation -> citation.filePath().contains("K9")));
        }
        assertEquals(2, requests.size(), "筛选图片不能重复检索或额外调用模型");
        assertEquals(20, requests.getFirst().body().path("max_images").asInt(), "无关图片不能先占满候选数量");
    }

    @Test
    void modelQueriesKeepCaptionMatchesAndOmitUnverifiableImages() {
        body = """
                {"status":"success","data":{"chunks":[],"images":[
                  {"id":"logo","caption":"logo"},
                  {"id":"light","caption":"gmd9300led_嵌入式筒灯产品图"}]}}
                """;
        var docs = new LightRagKnowledge(config, MAPPER).retrieve("GMD9300", null).block(WAIT);
        var images = (List<?>) docs.getFirst().getPayloadValue("images");
        assertEquals(1, images.size());
        assertEquals("light", ((java.util.Map<?, ?>) images.getFirst()).get("id"));
        assertTrue(new LightRagKnowledge(config, MAPPER).retrieve("GMD9312", null).block(WAIT).isEmpty());
    }

    @Test
    void handlesNaiveModeEmptySuccessAndExplicitNoResults() {
        config.setConnectionConfig(MAPPER.createObjectNode().put("baseUrl", baseUrl()));
        config.setRetrievalConfig(MAPPER.createObjectNode().put("mode", "naive").put("topK", 1));
        body = """
                {"status":"success","data":{"chunks":[{"content":"正文","chunk_id":"c","reference_id":"1"}],
                  "references":[{"reference_id":"1","file_path":"source.pdf"}]}}
                """;
        var docs = new LightRagKnowledge(config, MAPPER).retrieve("正文问题", null).block(WAIT);
        assertEquals(1, docs.size());
        assertTrue(docs.getFirst().getMetadata().getContentText().contains("source.pdf"));
        assertNull(requests.getFirst().apiKey());
        body = "{\"status\":\"success\",\"data\":{\"chunks\":[]}}";
        assertTrue(new LightRagKnowledge(config, MAPPER).retrieve("空检索", null).block(WAIT).isEmpty());
        body = "{\"status\":\"failure\",\"metadata\":{\"failure_reason\":\"no_results\"}}";
        assertTrue(new LightRagKnowledge(config, MAPPER).retrieve("空检索", null).block(WAIT).isEmpty());
    }

    @Test
    void surfacesFailuresAndMalformedResponsesWithoutExposingResponseOrCredentials() {
        for (String bad : List.of("{\"status\":\"failure\",\"message\":\"secret-test-key\"}",
                "{\"status\":\"success\",\"data\":{}}", "not json")) {
            body = bad;
            var error = assertThrows(IllegalStateException.class,
                    () -> new LightRagKnowledge(config, MAPPER).retrieve("检索失败", null).block(WAIT));
            assertFalse(error.getMessage().contains("secret-test-key"));
        }
        status = 403;
        body = "{\"detail\":\"secret-test-key\"}";
        var error = assertThrows(IllegalStateException.class,
                () -> new LightRagKnowledge(config, MAPPER).retrieve("认证失败", null).block(WAIT));
        assertTrue(error.getMessage().contains("403"));
        assertNull(error.getCause());
    }

    @Test
    void retriesTransientFailuresOnlyWhenConfiguredAndDoesNotRetryAuthentication() {
        config.setHttpConfig(MAPPER.createObjectNode().put("maxRetries", 2));
        transientFailures.set(1);
        assertFalse(new LightRagKnowledge(config, MAPPER).retrieve("重试查询", null).block(WAIT).isEmpty());
        assertEquals(2, requests.size());
        requests.clear();
        status = 401;
        assertThrows(IllegalStateException.class,
                () -> new LightRagKnowledge(config, MAPPER).retrieve("认证失败", null).block(WAIT));
        assertEquals(1, requests.size());
    }

    @Test
    void isolatesPerKnowledgeConnectionAndSnapshotsMutableConfiguration() {
        var first = new LightRagKnowledge(config, MAPPER);
        config.setConnectionConfig(MAPPER.createObjectNode().put("baseUrl", baseUrl()).put("apiKey", "other-key"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.getRetrievalConfig()).put("mode", "naive");
        var second = new LightRagKnowledge(config, MAPPER);
        first.retrieve("第一知识库", null).block(WAIT);
        second.retrieve("第二知识库", null).block(WAIT);
        assertEquals("test-key", requests.get(0).apiKey());
        assertEquals("hybrid", requests.get(0).body().path("mode").asText());
        assertEquals("other-key", requests.get(1).apiKey());
        assertEquals("naive", requests.get(1).body().path("mode").asText());
    }

    @Test
    void timesOutAndRejectsUnsupportedWritesAndInvalidInput() {
        config.setHttpConfig(MAPPER.createObjectNode().put("timeoutSeconds", 1));
        delayMs = 1500;
        var knowledge = new LightRagKnowledge(config, MAPPER);
        var error = assertThrows(IllegalStateException.class, () -> knowledge.retrieve("超时查询", null).block(WAIT));
        assertTrue(error.getMessage().contains("超时"));
        assertThrows(IllegalArgumentException.class, () -> knowledge.retrieve("   ", null).block(WAIT));
        assertThrows(UnsupportedOperationException.class, () -> knowledge.addDocuments(List.of()).block(WAIT));
    }

    @Test
    void rejectsInvalidConnectionModesAndRangesBeforeSendingAnything() {
        for (String url : List.of("", "file:///tmp/knowledge", "http://user:secret@localhost", "http://localhost?key=secret")) {
            config.setConnectionConfig(MAPPER.createObjectNode().put("baseUrl", url));
            assertThrows(IllegalArgumentException.class, () -> new LightRagKnowledge(config, MAPPER));
        }
        config.setConnectionConfig(MAPPER.createObjectNode().put("baseUrl", baseUrl()));
        config.setRetrievalConfig(MAPPER.createObjectNode().put("mode", "bypass"));
        assertThrows(IllegalArgumentException.class, () -> new LightRagKnowledge(config, MAPPER));
        config.setRetrievalConfig(MAPPER.createObjectNode().put("graphTopK", 0));
        assertThrows(IllegalArgumentException.class, () -> new LightRagKnowledge(config, MAPPER));
        config.setRetrievalConfig(MAPPER.createObjectNode().put("topK", 1.5));
        assertThrows(IllegalArgumentException.class, () -> new LightRagKnowledge(config, MAPPER));
        assertTrue(requests.isEmpty());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/site";
    }

    @Test
    void blocksStaleConnectionsAndPendingModelChangesBeforeQuerying() {
        var configs = org.mockito.Mockito.mock(com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService.class);
        var sync = org.mockito.Mockito.mock(com.gmd.faq.server.main.knowledge.service.LightRagModelSyncService.class);
        org.mockito.Mockito.when(configs.getById(config.getId())).thenReturn(config);
        var knowledge = new LightRagIKnowledge(MAPPER, sync, configs).build(config);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("须重启 LightRAG")).when(sync).ensureReady(config);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> knowledge.retrieve("pending", null).block(WAIT))
                .getMessage().contains("重启"));
        assertTrue(requests.isEmpty());
        org.mockito.Mockito.reset(sync);
        config.setConnectionConfig(MAPPER.createObjectNode().put("baseUrl", baseUrl()).put("apiKey", "rotated"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> knowledge.retrieve("changed", null).block(WAIT))
                .getMessage().contains("连接已变更"));
        org.mockito.Mockito.verifyNoInteractions(sync);
        assertTrue(requests.isEmpty());
    }

    @Test
    void propagatesTenantIntoModelValidationOnBoundedElastic() {
        com.gmd.faq.server.main.common.util.TenantUtils.setCurrentTenant(9L, "test");
        try {
            var knowledge = new LightRagKnowledge(config, MAPPER, () ->
                    assertEquals(9L, com.gmd.faq.server.main.common.util.TenantUtils.getCurrentTenantId()));
            assertNotNull(knowledge.retrieve("知识库", null).block(WAIT));
            assertEquals(1, requests.size());
        } finally { com.gmd.faq.server.main.common.util.TenantUtils.clear(); }
    }

    private record Request(String path, JsonNode body, String apiKey, String authorization, String workspace) {}
}
