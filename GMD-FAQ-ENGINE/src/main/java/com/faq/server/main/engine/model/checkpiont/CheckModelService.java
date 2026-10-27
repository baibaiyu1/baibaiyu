package com.gmd.faq.server.main.engine.model.checkpiont;

import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.engine.model.ChatModelFactory;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 描述：
 *
 **/
@RestController
@RequestMapping("/runtime/model/config")
@RequiredArgsConstructor
public class CheckModelService {
    private final ModelConfigService modelConfigService;
    private final ChatModelFactory chatModelFactory;
    private final com.gmd.faq.server.main.knowledge.service.KnowledgeModelService knowledgeModels;
    private final com.gmd.faq.server.main.engine.rag.EmbeddingService embeddings;

    @RequestMapping("/check/{modelId}")
    public R<CheckModelResult> checkModel(@PathVariable("modelId") Long modelId) {
        // 先更新状态为 CHECKING（独立事务，立即提交）
        updateConnectivityResult(modelId, "CHECKING", null);

        try {

            ModelWrapper config = modelConfigService.getModelWrapperById(modelId);
            var types = config.getConfig().getModelType();
            if (types != null && types.toString().contains("\"EMBEDDING\"")) {
                embeddings.embed("connection test", knowledgeModels.embeddingProbeConfig(modelId));
                updateConnectivityResult(modelId, "CONNECTED", null);
                return R.data(new CheckModelResult(true, "向量模型连接成功"));
            }
            if (types != null && types.toString().contains("\"RERANK\"")) {
                var spec = knowledgeModels.rerankProbeSpec(modelId);
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("model", spec.path("model").asText());
                boolean aliyun = "aliyun".equals(spec.path("binding").asText());
                if (aliyun) {
                    body.put("input", java.util.Map.of("query", "connection", "documents", java.util.List.of("connection test")));
                    body.put("parameters", java.util.Map.of("top_n", 1));
                } else {
                    body.put("query", "connection");
                    body.put("documents", java.util.List.of("connection test"));
                    body.put("top_n", 1);
                }
                var request = org.springframework.web.reactive.function.client.WebClient.create().post()
                        .uri(spec.path("host").asText()).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
                if (!spec.path("api_key").asText("").isBlank()) request.header("Authorization", "Bearer " + spec.path("api_key").asText());
                var result = request.bodyValue(body).retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class)
                        .timeout(java.time.Duration.ofSeconds(30)).block();
                var rows = result == null ? null : aliyun ? result.path("output").path("results") : result.path("results");
                if (rows == null || !rows.isArray() || rows.isEmpty() || !rows.get(0).path("relevance_score").isNumber())
                    throw new IllegalArgumentException("重排接口返回格式无效");
                updateConnectivityResult(modelId, "CONNECTED", null);
                return R.data(new CheckModelResult(true, "重排模型连接成功"));
            }

            ModelConfigWrapper configWrapper = new ModelConfigWrapper();
            config.getConfig().fillModelConfigWrapper(configWrapper);
            config.getProvider().fillModelConfigWrapper(configWrapper);

            Model simpleModel = chatModelFactory.getSimpleModel(configWrapper);

            ReActAgent agent = ReActAgent.builder()
                    .name("CHECK_MODEL_AGENT")
                    .model(simpleModel)
                    .sysPrompt("For user inquiries, you must always respond with \"Connection test successful.\"")
                    .build();

            Msg msg = Msg.builder().textContent("hello").build();
            Msg response = agent.call(msg).block();
            if (response == null || response.getTextContent() == null) {
                // 检测失败：无响应
                updateConnectivityResult(modelId, "FAILED", "模型无响应");
                return R.data(new CheckModelResult(false, "模型无响应"));
            }

            // 检测成功
            updateConnectivityResult(modelId, "CONNECTED", null);
            return R.data(new CheckModelResult(true, "连接成功"));
        } catch (Exception e) {
            // 检测失败：异常
            updateConnectivityResult(modelId, "FAILED", e.getMessage());
            return R.data(new CheckModelResult(false, e.getMessage()));
        }
    }

    /**
     * 更新连接性检测结果到数据库
     */
    private void updateConnectivityResult(Long modelId, String status, String message) {
        ModelConfig entity = new ModelConfig();
        entity.setId(modelId);
        entity.setConnectivityStatus(status);
        entity.setConnectivityMessage(message);
        entity.setLastConnectivityCheck(LocalDateTime.now());
        modelConfigService.updateById(entity);
    }
}
