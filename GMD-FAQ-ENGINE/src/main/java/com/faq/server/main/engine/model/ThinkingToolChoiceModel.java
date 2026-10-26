package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.common.model.ReasoningConfigSupport;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/** DeepSeek 推理模式不支持强制工具选择；保留推理，并在实际请求边界使用兼容策略。 */
public final class ThinkingToolChoiceModel implements Model {
    private final Model delegate;
    private final GenerateOptions configuredOptions;

    private ThinkingToolChoiceModel(Model delegate, GenerateOptions configuredOptions) {
        this.delegate = delegate;
        this.configuredOptions = configuredOptions;
    }

    public static Model wrap(Model model, ModelConfigWrapper config, GenerateOptions options) {
        var caps = ReasoningConfigSupport.capabilities(config.getProvider(), config.getBaseUrl(),
                config.getModelCode(), config.getApiType(), config.getReasoningProfile());
        return "DEEPSEEK".equals(caps.profile()) ? new ThinkingToolChoiceModel(model, options) : model;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        GenerateOptions effective = GenerateOptions.mergeOptions(options, configuredOptions);
        ToolChoice choice = effective.getToolChoice();
        if (thinkingDisabled(effective) || !(choice instanceof ToolChoice.Required || choice instanceof ToolChoice.Specific))
            return delegate.stream(messages, tools, options);

        List<ToolSchema> available = tools == null ? List.of() : tools;
        if (choice instanceof ToolChoice.Specific specific) {
            // 自动选择时也不能引入指定范围外的工具，更不能恢复被权限或方案模式移除的工具。
            available = available.stream().filter(tool -> tool.getName().equals(specific.toolName())).toList();
            if (available.isEmpty()) return Flux.error(new IllegalArgumentException("指定工具在当前运行中不可用，请检查工具绑定及权限"));
        }
        GenerateOptions compatible = GenerateOptions.mergeOptions(GenerateOptions.builder()
                .toolChoice(available.isEmpty() ? new ToolChoice.None() : new ToolChoice.Auto()).build(), effective);
        return delegate.stream(messages, available, compatible);
    }

    private static boolean thinkingDisabled(GenerateOptions options) {
        Map<String, Object> body = options.getAdditionalBodyParams();
        if ("none".equals(options.getReasoningEffort())) return true;
        if (body == null) return false;
        Object thinking = body.get("thinking");
        return "none".equals(body.get("reasoning.effort")) || "none".equals(body.get("reasoning_effort"))
                || thinking instanceof Map<?, ?> value && "disabled".equals(value.get("type"));
    }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
