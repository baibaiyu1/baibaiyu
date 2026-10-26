package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;

/** Responses 独立映射参数；项目负责历史和重试预算，不依赖供应商保存的会话。 */
public final class ResponsesModelOptions {
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> BODY_KEYS = Set.of("store", "reasoning.effort", "reasoning.summary", "reasoning.context",
            "reasoning.mode", "max_tool_calls", "service_tier", "prompt_cache_key", "prompt_cache_options", "safety_identifier");
    private ResponsesModelOptions() {}

    public static GenerateOptions create(ModelConfigWrapper config, int timeoutSeconds, boolean probe) {
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.apply(config);
        if (config.getQueryParams() != null && !config.getQueryParams().isEmpty())
            throw new IllegalArgumentException("Responses 协议不支持额外查询参数，请使用请求头或支持的请求体参数");
        var body = new LinkedHashMap<String, Object>();
        if (config.getBodyParams() != null) body.putAll(config.getBodyParams());
        if (body.containsKey("previous_response_id"))
            throw new IllegalArgumentException("Responses 会话历史由本系统管理，不能配置 previous_response_id");
        if (body.containsKey("store") && !"false".equalsIgnoreCase(String.valueOf(body.get("store"))))
            throw new IllegalArgumentException("Responses 会话历史由本系统管理，store 必须为 false");
        for (String key : body.keySet()) {
            if (!BODY_KEYS.contains(key)) throw new IllegalArgumentException("Responses 不支持请求体参数：" + key);
        }
        body.put("store", false);
        Object effort = body.remove("reasoning.effort");
        if (effort != null && !EFFORTS.contains(String.valueOf(effort)))
            throw new IllegalArgumentException("Responses 推理强度无效");
        var builder = GenerateOptions.builder()
                .stream(!probe && Boolean.TRUE.equals(config.getStreaming()))
                .maxTokens(probe && config.getReasoning() == null ? 256 : config.getMaxTokens())
                .additionalBodyParams(body)
                .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(timeoutSeconds))
                        .maxAttempts(1).build());
        if (Boolean.TRUE.equals(config.getResponsesSampling())) {
            if (config.getTemperature() != null) builder.temperature(config.getTemperature());
            if (config.getTopP() != null) builder.topP(config.getTopP());
        }
        if (effort != null) builder.reasoningEffort(String.valueOf(effort));
        if (probe || config.getToolChoiceStrategy() == com.gmd.faq.server.main.common.enums.ToolChoiceStrategy.NONE)
            builder.toolChoice(new ToolChoice.None());
        else if (config.getToolChoiceStrategy() == com.gmd.faq.server.main.common.enums.ToolChoiceStrategy.REQUIRED)
            builder.toolChoice(new ToolChoice.Required());
        else if (config.getToolChoiceStrategy() == com.gmd.faq.server.main.common.enums.ToolChoiceStrategy.SPECIFIC)
            builder.toolChoice(new ToolChoice.Specific(config.getSpecificToolName()));
        else builder.toolChoice(new ToolChoice.Auto());
        return builder.build();
    }
}
