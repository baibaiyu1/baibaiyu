package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 内置服务的地址和凭证只来自部署配置；兼容已有的第三方连接。 */
@Component
public class LightRagConnectionResolver {
    private final String builtinBaseUrl;
    private final String builtinApiKey;

    public LightRagConnectionResolver(
            @Value("${LIGHTRAG_BASE_URL:http://127.0.0.1:9621}") String builtinBaseUrl,
            @Value("${LIGHTRAG_API_KEY:}") String builtinApiKey) {
        this.builtinBaseUrl = builtinBaseUrl;
        this.builtinApiKey = builtinApiKey;
    }

    public record Connection(String baseUrl, String apiKey, boolean builtin) {
        public ObjectNode json() {
            return JsonNodeFactory.instance.objectNode().put("baseUrl", baseUrl).put("apiKey", apiKey);
        }

        @Override
        public String toString() {
            return "LightRagConnection[baseUrl=" + baseUrl + ", builtin=" + builtin + "]";
        }
    }

    public Connection resolve(KnowledgeBaseConfig config) {
        ObjectNode connection = normalize(config.getConnectionConfig());
        if (isBuiltin(connection)) {
            if (builtinApiKey == null || builtinApiKey.isBlank())
                throw new IllegalArgumentException("内置知识图谱尚未完成部署，请联系管理员检查服务配置");
            return new Connection(validUrl(builtinBaseUrl), builtinApiKey.trim(), true);
        }
        return new Connection(connection.path("baseUrl").asText(), connection.path("apiKey").asText(""), false);
    }

    public static boolean isBuiltin(JsonNode connection) {
        if (connection == null || connection.isNull()) return true;
        if (!connection.isObject()) throw new IllegalArgumentException("知识图谱连接配置须为 JSON 对象");
        String mode = connection.path("mode").asText("").trim().toUpperCase(Locale.ROOT);
        if (mode.equals("BUILTIN")) return true;
        if (mode.equals("EXTERNAL")) return false;
        if (!mode.isEmpty()) throw new IllegalArgumentException("知识图谱连接方式无效");
        return connection.path("baseUrl").asText("").isBlank();
    }

    public static ObjectNode normalize(JsonNode connection) {
        boolean builtin = isBuiltin(connection);
        ObjectNode normalized = connection == null || connection.isNull()
                ? JsonNodeFactory.instance.objectNode() : connection.deepCopy();
        normalized.put("mode", builtin ? "BUILTIN" : "EXTERNAL");
        if (builtin) normalized.remove(List.of("baseUrl", "apiKey"));
        else {
            normalized.put("baseUrl", validUrl(normalized.path("baseUrl").asText("")));
            normalized.put("apiKey", normalized.path("apiKey").asText("").trim());
        }
        return normalized;
    }

    private static String validUrl(String value) {
        try {
            String base = value.trim().replaceAll("/+$", "");
            URI uri = URI.create(base);
            if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return base;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("请配置有效的知识图谱 HTTP(S) 服务地址");
        }
    }
}
