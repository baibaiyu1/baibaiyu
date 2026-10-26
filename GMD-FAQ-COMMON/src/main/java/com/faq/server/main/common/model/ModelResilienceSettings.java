package com.gmd.faq.server.main.common.model;

import com.fasterxml.jackson.databind.JsonNode;

/** 最大尝试次数包含首次调用；空配置保持 SDK 原路径。 */
public record ModelResilienceSettings(int maxAttempts, Long fallbackModelId, Long summaryModelId) {
    public static ModelResilienceSettings parse(JsonNode config) {
        if (config == null || config.isNull()) return new ModelResilienceSettings(3, null, null);
        if (!config.isObject()) throw new IllegalArgumentException("模型容错配置必须为对象");
        JsonNode attempts = config.get("maxAttempts");
        int count = attempts == null ? 3 : attempts.isIntegralNumber() && attempts.canConvertToInt() ? attempts.asInt() : 0;
        if (count < 1 || count > 5) throw new IllegalArgumentException("模型最大尝试次数必须为 1 到 5");
        return new ModelResilienceSettings(count, id(config.get("fallbackModelId")), id(config.get("summaryModelId")));
    }
    private static Long id(JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try {
            String text = value.asText();
            if (!text.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long id = Long.parseLong(text);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("模型编号无效"); }
    }
}
