package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.engine.telemetry.AgentTelemetry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 每次请求独立预算，只在首个响应前重试；供应商内部重试设为一次，避免嵌套预算。 */
public final class ResilientModel implements Model {
    public static final String EVENTS_KEY = "gmd.model.resilience.events";
    public static final String MODEL_KEY = "gmd.model.resilience.actual-model";
    private final Model primary;
    private final Model fallback;
    private final int maxAttempts;
    private final AgentTelemetry telemetry;
    private final long agentId;

    public ResilientModel(Model primary, Model fallback, int maxAttempts, AgentTelemetry telemetry, long agentId) {
        this.primary = primary; this.fallback = fallback; this.maxAttempts = maxAttempts;
        this.telemetry = telemetry; this.agentId = agentId;
        if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("模型尝试次数无效");
        if (fallback != null && (primary.supportsNativeStructuredOutput() && !fallback.supportsNativeStructuredOutput()
                || primary.supportsNativeStructuredOutputWithTools() && !fallback.supportsNativeStructuredOutputWithTools()
                || fallback.getContextWindowSize() < primary.getContextWindowSize())) {
            throw new IllegalArgumentException("备用模型不兼容主模型的结构化输出或上下文能力");
        }
    }

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ExecutionConfig execution = ExecutionConfig.mergeConfigs(ExecutionConfig.builder().maxAttempts(1).build(),
                options == null ? null : options.getExecutionConfig());
        GenerateOptions once = GenerateOptions.mergeOptions(GenerateOptions.builder().executionConfig(execution).build(), options);
        return Flux.deferContextual(context -> {
            Consumer<Map<String,Object>> events = context.getOrDefault(EVENTS_KEY, value -> {});
            return attempt(primary, messages, tools, once, 1, false, events);
        });
    }

    private Flux<ChatResponse> attempt(Model model, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options,
            int number, boolean backup, Consumer<Map<String,Object>> events) {
        return Flux.deferContextual(context -> {
            Consumer<String> selected = context.getOrDefault(MODEL_KEY, name -> {});
            selected.accept(model.getModelName());
            return model.stream(messages, tools, options);
        }).switchOnFirst((signal, stream) -> {
            if (!signal.isOnError() || !isRetryable(signal.getThrowable())) return stream;
            Throwable error = signal.getThrowable();
            if (!backup && number < maxAttempts) {
                return Mono.delay(Duration.ofMillis(Math.min(2000, 200L << (number - 1))))
                        .flatMapMany(ignored -> {
                            transition("MODEL_RETRY", model, number + 1, error, events);
                            return attempt(model, messages, tools, options, number + 1, false, events);
                        });
            }
            if (!backup && fallback != null) {
                transition("MODEL_SWITCH", fallback, 1, error, events);
                return attempt(fallback, messages, tools, options, 1, true, events);
            }
            return stream;
        });
    }

    /** 官方 Responses 异常在网络失败时实现 ModelHttpException 但没有状态码，必须继续检查原因。 */
    private static boolean isRetryable(Throwable error) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof io.agentscope.core.model.transport.HttpTransportException http) return http.isRetryable();
            if (current instanceof ModelHttpException http && http.getStatusCode() != null) return http.isRetryableHttpStatus();
            if (current instanceof java.io.IOException || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof com.openai.errors.OpenAIIoException
                    || current instanceof com.openai.errors.OpenAIRetryableException) return true;
        }
        return false;
    }

    private void transition(String operation, Model model, int attempt, Throwable error, Consumer<Map<String,Object>> events) {
        telemetry.transition(agentId, operation, model.getModelName(), error);
        events.accept(Map.of("operation", operation, "model", model.getModelName(), "attempt", attempt,
                "errorType", error.getClass().getSimpleName()));
    }
    @Override public String getModelName() { return primary.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return primary.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return primary.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return primary.getContextWindowSize(); }
}
