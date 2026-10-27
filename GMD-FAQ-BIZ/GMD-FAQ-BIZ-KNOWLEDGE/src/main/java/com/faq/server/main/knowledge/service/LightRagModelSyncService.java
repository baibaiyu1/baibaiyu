package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

/** 在文档管理和检索前校验/同步模型，禁止只保存选择却继续调用旧模型。 */
@Service
@RequiredArgsConstructor
public class LightRagModelSyncService {
    private final KnowledgeModelService models;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public JsonNode sync(KnowledgeBaseConfig config) {
        JsonNode payload = models.lightRagPayload(config);
        if (payload == null) return null;
        JsonNode connection = config.getConnectionConfig();
        String base = connection.path("baseUrl").asText("").trim().replaceAll("/+$", "");
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("知识图谱服务地址无效");
        var factory = new JdkClientHttpRequestFactory(HTTP);
        factory.setReadTimeout(Duration.ofSeconds(30));
        var client = RestClient.builder().requestFactory(factory).build();
        try {
            return client.put().uri(base + "/models/config").contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-Key", connection.path("apiKey").asText(""))
                    .body(payload).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new IllegalArgumentException("LightRAG 尚不支持平台模型同步，请更新适配后的 LightRAG 服务");
                        if (status == 409) throw new IllegalArgumentException("LightRAG 模型同步冲突：请确认该实例未绑定其他知识库，或等待当前任务结束");
                        if (status < 200 || status >= 300) throw new IllegalArgumentException("LightRAG 模型同步失败（HTTP " + status + "）");
                        JsonNode result = response.bodyTo(JsonNode.class);
                        if (result == null || !"success".equals(result.path("status").asText()))
                            throw new IllegalArgumentException("LightRAG 模型同步响应无效");
                        return result;
                    });
        } catch (RestClientException ex) {
            throw new IllegalArgumentException("无法同步 LightRAG 模型，请检查服务地址、API 密钥和服务版本");
        }
    }

    public void ensureReady(KnowledgeBaseConfig config) {
        JsonNode result = sync(config);
        if (result != null && result.path("restart_required").asBoolean())
            throw new IllegalArgumentException("模型配置已同步；向量模型或模型角色变更须重启 LightRAG 后生效，已有向量数据须先使用 lightrag-rebuild-vdb 重建");
    }
}
