package com.gmd.faq.server.main.engine.telemetry;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.middleware.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RequiredArgsConstructor
public class AgentTelemetryMiddleware implements MiddlewareBase {
    private final AgentTelemetry telemetry;
    private final long agentId;
    private final boolean root;
    @Override public int order() { return -100; }
    @Override public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext rc,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            var scope = telemetry.scope(rc.get(AgentContext.class),agentId);
            if (scope==null || !root) return next.apply(input);
            String id = telemetry.begin(scope,"RUN",null); long start = System.nanoTime();
            var result = new AtomicReference<>("SUCCEEDED"); var error = new AtomicReference<Throwable>();
            return next.apply(input).doOnNext(event -> {
                if (event instanceof RequireUserConfirmEvent) result.set("WAITING");
                if (event instanceof AgentResultEvent ended && ended.getResult()!=null
                        && String.valueOf(ended.getResult().getGenerateReason()).contains("INTERRUPT")) result.set("STOPPED");
            }).doOnError(e -> { result.set("FAILED"); error.set(e); })
                    .doFinally(signal -> telemetry.end(id,signal==reactor.core.publisher.SignalType.CANCEL?"CANCELLED":result.get(),start,null,
                            error.get()==null?null:rc.get("gmd.telemetry.phase")==null?"AGENT":rc.get("gmd.telemetry.phase"),error.get()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @Override public Flux<AgentEvent> onActing(Agent agent,RuntimeContext rc,ActingInput input,Function<ActingInput,Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            var scope=telemetry.scope(rc.get(AgentContext.class),agentId);
            if (scope==null) return next.apply(input);
            String id=telemetry.begin(scope,"TOOLS",null);long start=System.nanoTime();var state=new AtomicReference<>("SUCCEEDED");
            rc.put("gmd.telemetry.phase","TOOLS");
            var error=new AtomicReference<Throwable>();
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ToolResultEndEvent ended && ended.getState()==io.agentscope.core.message.ToolResultState.ERROR) state.set("FAILED");
                if (event instanceof RequireUserConfirmEvent) state.set("WAITING");
            }).doOnError(e -> { state.set("FAILED");error.set(e); })
                    .doFinally(signal -> telemetry.end(id,signal==reactor.core.publisher.SignalType.CANCEL?"CANCELLED":state.get(),start,null,
                            "FAILED".equals(state.get())?"TOOLS":null,error.get()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @Override public Flux<AgentEvent> onModelCall(Agent agent,RuntimeContext rc,ModelCallInput input,Function<ModelCallInput,Flux<AgentEvent>> next) {
        if (telemetry.scope(rc.get(AgentContext.class),agentId)==null) return next.apply(input);
        rc.put("gmd.telemetry.phase","MODEL");
        var actual=new AtomicReference<io.agentscope.core.model.ChatUsage>();
        var actualModel = new AtomicReference<>(input.model().getModelName());
        var transitions = new java.util.concurrent.CopyOnWriteArrayList<Map<String,Object>>();
        var captured=new CapturedUsageModel(input.model(),actual);
        return next.apply(new ModelCallInput(input.messages(),input.tools(),input.options(),captured)).concatMap(event -> {
            if (!(event instanceof ModelCallEndEvent ended)) return Flux.just(event);
            var u=actual.get();
            var usageEvent = new CustomEvent("MODEL_USAGE",u==null?Map.of("replyId",ended.getReplyId(),"available",false):
                    Map.of("replyId",ended.getReplyId(),"available",true,"model",actualModel.get(),
                            "inputTokens",u.getInputTokens(),"outputTokens",u.getOutputTokens(),"reasoningTokens",u.getReasoningTokens(),"cachedTokens",u.getCachedTokens()));
            return Flux.concat(Flux.just(event), Flux.fromIterable(transitions)
                    .map(value -> (AgentEvent)new CustomEvent((String)value.get("operation"), value)), Flux.just(usageEvent));
        }).contextWrite(context -> context.put(com.gmd.faq.server.main.engine.model.ResilientModel.EVENTS_KEY,
                (java.util.function.Consumer<Map<String,Object>>)transitions::add)
                .put(com.gmd.faq.server.main.engine.model.ResilientModel.MODEL_KEY,
                        (java.util.function.Consumer<String>)actualModel::set));
    }
    /** SDK 会为缺失 usage 构造零值，事件必须区分“真实零值”和“未上报”。 */
    private record CapturedUsageModel(io.agentscope.core.model.Model delegate,
            AtomicReference<io.agentscope.core.model.ChatUsage> usage) implements io.agentscope.core.model.Model {
        @Override public Flux<io.agentscope.core.model.ChatResponse> stream(java.util.List<io.agentscope.core.message.Msg> messages,
                java.util.List<io.agentscope.core.model.ToolSchema> tools,io.agentscope.core.model.GenerateOptions options) {
            return delegate.stream(messages,tools,options).doOnNext(chunk -> { if (chunk.getUsage()!=null) usage.set(chunk.getUsage()); });
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
        @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
        @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
    }
}
