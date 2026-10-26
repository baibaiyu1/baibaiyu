package com.gmd.faq.server.main.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import java.util.Set;
import org.springframework.beans.BeanUtils;

/** 智能体自己的检索策略；连接及文档权限仍属于知识库，覆盖只应用于运行时副本。 */
public record KnowledgeRetrievalSettings(String ragMode, Integer topK, Double scoreThreshold,
        int maxCallsPerRound, int timeoutSeconds, int concurrency, int maxContextChars, int maxChunkChars,
        boolean includeImages, int maxImages, int historyTurns, boolean strictEvidence,
        Integer maxRetries, JsonNode lightRag) {
    public static KnowledgeRetrievalSettings parse(JsonNode node) {
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        JsonNode config = node == null || node.isNull() ? empty : node;
        if (!config.isObject()) throw new IllegalArgumentException("知识库调用策略必须为对象");
        String mode = text(config, "ragMode", "AGENTIC", Set.of("GENERIC", "AGENTIC"));
        Integer topK = optionalInt(config, "topK", 1, 1000);
        Double threshold = null;
        if (present(config, "scoreThreshold")) {
            JsonNode value = config.get("scoreThreshold");
            if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0 || value.asDouble() > 1) {
                throw new IllegalArgumentException("知识库分数阈值必须在 0 到 1 之间");
            }
            threshold = value.asDouble();
        }
        int context = integer(config, "maxContextChars", 30000, 2000, 120000);
        int chunk = integer(config, "maxChunkChars", 6000, 500, 20000);
        if (chunk > context) throw new IllegalArgumentException("单个片段字符上限不能超过总证据字符上限");
        JsonNode light = present(config, "lightRag") ? config.get("lightRag") : empty;
        if (!light.isObject()) throw new IllegalArgumentException("知识图谱检索覆盖必须为对象");
        Set<String> lightKeys = Set.of("mode", "graphTopK", "maxEntityTokens", "maxRelationTokens", "maxTotalTokens", "enableRerank");
        light.fieldNames().forEachRemaining(key -> {
            if (!lightKeys.contains(key)) throw new IllegalArgumentException("不支持的知识图谱检索配置：" + key);
        });
        if (present(light, "mode")) text(light, "mode", "mix", Set.of("naive", "mix", "local", "global", "hybrid"));
        optionalInt(light, "graphTopK", 1, 1000);
        for (String key : Set.of("maxEntityTokens", "maxRelationTokens", "maxTotalTokens")) optionalInt(light, key, 1, 1000000);
        bool(light, "enableRerank", true);
        return new KnowledgeRetrievalSettings(mode, topK, threshold,
                integer(config, "maxCallsPerRound", 3, 1, 8), integer(config, "timeoutSeconds", 60, 1, 180),
                integer(config, "concurrency", 4, 1, 4), context, chunk, bool(config, "includeImages", true),
                integer(config, "maxImages", 3, 1, 20), integer(config, "historyTurns", 0, 0, 4),
                bool(config, "strictEvidence", true), optionalInt(config, "maxRetries", 0, 3), light.deepCopy());
    }

    public KnowledgeBaseConfig apply(KnowledgeBaseConfig source) {
        KnowledgeBaseConfig copy = new KnowledgeBaseConfig();
        BeanUtils.copyProperties(source, copy);
        ObjectNode retrieval = object(source.getRetrievalConfig());
        if (topK != null) retrieval.put("topK", topK);
        if (scoreThreshold != null) {
            retrieval.put("scoreThreshold", scoreThreshold);
            if (source.getKbType() == KbType.RAGFLOW) retrieval.put("similarityThreshold", scoreThreshold);
        }
        if (source.getKbType() == KbType.LIGHTRAG) {
            lightRag.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) retrieval.set(entry.getKey(), entry.getValue().deepCopy());
            });
            retrieval.put("includeImages", includeImages);
            retrieval.put("maxImages", maxImages);
            ObjectNode http = object(source.getHttpConfig());
            if (maxRetries != null) http.put("maxRetries", maxRetries);
            copy.setHttpConfig(http);
        }
        copy.setRetrievalConfig(retrieval);
        return copy;
    }

    private static ObjectNode object(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : JsonNodeFactory.instance.objectNode();
    }
    private static boolean present(JsonNode config, String key) { return config.hasNonNull(key); }
    private static Integer optionalInt(JsonNode config, String key, int min, int max) {
        if (!present(config, key)) return null;
        JsonNode value = config.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new IllegalArgumentException("知识库 " + key + " 必须为 " + min + " 到 " + max + " 之间的整数");
        }
        return value.intValue();
    }
    private static int integer(JsonNode config, String key, int fallback, int min, int max) {
        Integer value = optionalInt(config, key, min, max);
        return value == null ? fallback : value;
    }
    private static boolean bool(JsonNode config, String key, boolean fallback) {
        if (!present(config, key)) return fallback;
        if (!config.get(key).isBoolean()) throw new IllegalArgumentException("知识库 " + key + " 必须为布尔值");
        return config.get(key).booleanValue();
    }
    private static String text(JsonNode config, String key, String fallback, Set<String> values) {
        if (!present(config, key)) return fallback;
        if (!config.get(key).isTextual() || !values.contains(config.get(key).asText())) {
            throw new IllegalArgumentException("知识库 " + key + " 配置无效");
        }
        return config.get(key).asText();
    }
}
