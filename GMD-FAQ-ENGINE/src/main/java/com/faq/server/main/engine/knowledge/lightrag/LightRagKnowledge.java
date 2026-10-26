package com.gmd.faq.server.main.engine.knowledge.lightrag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.engine.agui.AgentContextPropagation;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 只调用 /query/data，最终回答由平台智能体生成。
 * 一个连接对应服务启动时绑定的 workspace，不通过请求头切换数据范围。
 * Top K 限制文本片段数；非空图谱额外返回一条上下文，确保 SDK 注入实体和关系正文。
 */
public final class LightRagKnowledge implements Knowledge {
    private static final Set<String> MODES = Set.of("local", "global", "hybrid", "mix", "naive");
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final WebClient client;
    private final URI queryUri;
    private final String knowledgeId;
    private final String knowledgeName;
    private final ObjectNode retrieval;
    private final Duration timeout;
    private final int maxRetries;

    public LightRagKnowledge(KnowledgeBaseConfig config, ObjectMapper mapper) {
        if (config == null || config.getConnectionConfig() == null || !config.getConnectionConfig().isObject()) {
            throw new IllegalArgumentException("知识图谱连接配置不能为空");
        }
        this.mapper = mapper;
        JsonNode connection = config.getConnectionConfig();
        String baseUrl = connection.path("baseUrl").asText("").trim().replaceAll("/+$", "");
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("知识图谱服务地址格式无效");
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("知识图谱服务地址须为 HTTP(S) 地址，且不能包含账号、查询参数或锚点");
        }
        this.queryUri = URI.create(baseUrl + "/query/data");
        this.knowledgeId = String.valueOf(config.getId());
        this.knowledgeName = config.getName() == null ? "知识图谱" : config.getName();
        if (config.getRetrievalConfig() != null && !config.getRetrievalConfig().isObject()
                || config.getHttpConfig() != null && !config.getHttpConfig().isObject()) {
            throw new IllegalArgumentException("知识图谱检索和 HTTP 配置须为 JSON 对象");
        }
        this.retrieval = config.getRetrievalConfig() == null
                ? mapper.createObjectNode() : config.getRetrievalConfig().deepCopy();
        String mode = retrieval.path("mode").asText("mix");
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("知识图谱检索模式无效");
        }
        retrieval.put("mode", mode);
        integer(retrieval, "topK", 20, 1, 1000);
        integer(retrieval, "graphTopK", 60, 1, 1000);
        integer(retrieval, "maxEntityTokens", 6000, 1, 1_000_000);
        integer(retrieval, "maxRelationTokens", 8000, 1, 1_000_000);
        integer(retrieval, "maxTotalTokens", 30000, 1, 1_000_000);
        if (retrieval.has("enableRerank") && !retrieval.get("enableRerank").isBoolean()) {
            throw new IllegalArgumentException("知识图谱重排开关须为布尔值");
        }
        JsonNode http = config.getHttpConfig() == null ? mapper.createObjectNode() : config.getHttpConfig();
        this.timeout = Duration.ofSeconds(integer(http, "timeoutSeconds", 180, 1, 600));
        this.maxRetries = integer(http, "maxRetries", 0, 0, 3);
        WebClient.Builder builder = WebClient.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        String apiKey = connection.path("apiKey").asText("").trim();
        if (!apiKey.isEmpty()) builder.defaultHeader("X-API-Key", apiKey);
        this.client = builder.build();
        AgentContextPropagation.initialize();
    }

    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(new UnsupportedOperationException("请在知识图谱的文档管理页面中管理文档"));
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.defer(() -> {
            if (query == null || query.isBlank()) {
                return Mono.error(new IllegalArgumentException("检索内容不能为空"));
            }
            int limit = config == null ? integer(retrieval, "topK", 20, 1, 1000) : config.getLimit();
            if (limit < 1 || limit > 1000) {
                return Mono.error(new IllegalArgumentException("知识图谱返回片段数须在 1 到 1000 之间"));
            }
            ObjectNode request = mapper.createObjectNode();
            request.put("query", query.trim());
            request.put("mode", retrieval.path("mode").asText());
            request.put("chunk_top_k", limit);
            request.put("top_k", integer(retrieval, "graphTopK", 60, 1, 1000));
            request.put("max_entity_tokens", integer(retrieval, "maxEntityTokens", 6000, 1, 1_000_000));
            request.put("max_relation_tokens", integer(retrieval, "maxRelationTokens", 8000, 1, 1_000_000));
            request.put("max_total_tokens", integer(retrieval, "maxTotalTokens", 30000, 1, 1_000_000));
            request.put("enable_rerank", retrieval.path("enableRerank").asBoolean(true));
            request.put("include_images", retrieval.path("includeImages").asBoolean(true));
            request.put("max_images", LightRagQuerySupport.candidateLimit(query,
                    integer(retrieval, "maxImages", 3, 1, 20)));
            Mono<JsonNode> response = client.post().uri(queryUri).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request).exchangeToMono(res -> {
                        int status = res.statusCode().value();
                        if (status < 200 || status >= 300) {
                            return res.releaseBody().then(Mono.error(new RequestFailure(
                                    "知识图谱检索请求失败（HTTP " + status + "）", status == 429 || status >= 500)));
                        }
                        return res.bodyToMono(JsonNode.class)
                                .switchIfEmpty(Mono.error(new RequestFailure("知识图谱返回了空响应", false)));
                    }).timeout(timeout);
            if (maxRetries > 0) {
                response = response.retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(200))
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(error -> error instanceof WebClientRequestException || error instanceof TimeoutException
                                || error instanceof RequestFailure failure && failure.retryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
            }
            return response.map(body -> convert(body, limit, query))
                    .onErrorMap(error -> error instanceof RequestFailure ? error : new RequestFailure(
                            error instanceof TimeoutException ? "知识图谱检索超时" : "知识图谱检索失败，请检查连接配置与服务状态", false));
        }).contextCapture();
    }

    private List<Document> convert(JsonNode response, int limit, String query) {
        if ("failure".equals(response.path("status").asText())
                && "no_results".equals(response.path("metadata").path("failure_reason").asText())) {
            return List.of();
        }
        if (!"success".equals(response.path("status").asText()) || !response.path("data").isObject()) {
            throw new RequestFailure("知识图谱检索未成功，请检查服务状态", false);
        }
        JsonNode data = response.get("data");
        if (!data.path("chunks").isArray()) {
            throw new RequestFailure("知识图谱检索结果格式无效", false);
        }
        List<Map<String, Object>> entities = rows(data, "entities");
        List<Map<String, Object>> relationships = rows(data, "relationships");
        List<Map<String, Object>> references = rows(data, "references");
        Set<String> graphSources = LightRagQuerySupport.graphSources(query, entities, relationships);
        List<Document> documents = new ArrayList<>();
        if (!entities.isEmpty() || !relationships.isEmpty()) {
            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("entities", entities);
            graph.put("relationships", relationships);
            graph.put("references", references);
            String text;
            try {
                text = "[知识图谱上下文]\n" + mapper.writeValueAsString(graph);
            } catch (Exception e) {
                throw new RequestFailure("知识图谱结果格式无效", false);
            }
            documents.add(document(text, "graph", "知识图谱上下文", "graph", "", List.of(), Map.of("graph", graph)));
        }
        int count = 0;
        for (JsonNode chunk : data.get("chunks")) {
            if (count >= limit) break;
            String content = chunk.path("content").asText("");
            if (content.isBlank()) continue;
            String referenceId = chunk.path("reference_id").asText("");
            String filePath = chunk.path("file_path").asText("");
            if (filePath.isBlank()) {
                filePath = references.stream().filter(ref -> referenceId.equals(String.valueOf(ref.get("reference_id"))))
                        .map(ref -> String.valueOf(ref.getOrDefault("file_path", ""))).findFirst().orElse("");
            }
            if (!LightRagQuerySupport.relevantChunk(query, content, filePath, graphSources)) continue;
            // 来源写入正文，AgentScope 的标准知识注入只读取 content，不读取 payload。
            String text = "[知识图谱来源 " + referenceId + "] " + filePath + "\n" + content;
            documents.add(document(text, chunk.path("chunk_id").asText("chunk-" + count),
                    filePath, "chunk", referenceId, references));
            count++;
        }
        List<Map<String, Object>> images = retrieval.path("includeImages").asBoolean(true)
                ? LightRagQuerySupport.select(query, rows(data, "images"), entities, relationships,
                        integer(retrieval, "maxImages", 3, 1, 20)) : List.of();
        if (!images.isEmpty()) {
            if (documents.isEmpty()) {
                documents.add(document("[知识图谱相关图片]", "images", "", "images", "", references));
            }
            Document first = documents.getFirst();
            Map<String, Object> payload = new LinkedHashMap<>(first.getPayload());
            payload.put("images", images);
            Document replacement = new Document(DocumentMetadata.builder()
                    .content(first.getMetadata().getContent()).docId(first.getMetadata().getDocId())
                    .chunkId(first.getMetadata().getChunkId()).payload(payload).build());
            replacement.setScore(first.getScore());
            documents.set(0, replacement);
        }
        return documents;
    }

    private Document document(String content, String chunkId, String source, String kind,
                              String referenceId, List<Map<String, Object>> references) {
        return document(content, chunkId, source, kind, referenceId, references, Map.of());
    }

    private Document document(String content, String chunkId, String source, String kind,
                              String referenceId, List<Map<String, Object>> references, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("knowledgeType", "LIGHTRAG");
        payload.put("knowledgeId", knowledgeId);
        payload.put("knowledgeName", knowledgeName);
        payload.put("contentType", kind);
        payload.put("fileName", source);
        payload.put("file_path", source);
        payload.put("reference_id", referenceId);
        payload.put("references", references);
        Document doc = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text(content).build())
                .docId("lightrag:" + knowledgeId + ":" + source)
                .chunkId(chunkId).payload(payload).build());
        // LightRAG 不提供统一相关度分数；保持未知，不伪造分数或套用平台阈值。
        doc.setScore(null);
        return doc;
    }

    private List<Map<String, Object>> rows(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) throw new RequestFailure("知识图谱检索结果格式无效", false);
        return mapper.convertValue(value, ROWS);
    }

    private static int integer(JsonNode config, String key, int fallback, int min, int max) {
        JsonNode value = config.get(key);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new IllegalArgumentException("知识图谱 " + key + " 须为 " + min + " 到 " + max + " 之间的整数");
        }
        return value.intValue();
    }

    private static final class RequestFailure extends IllegalStateException {
        private final boolean retryable;

        private RequestFailure(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
    }
}
