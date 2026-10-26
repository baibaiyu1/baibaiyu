package com.gmd.faq.server.main.engine.telemetry;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** 流式 usage 通常为累计快照，只取最后一次供应商快照；缺失时保持未知。 */
public record TelemetryModel(Model delegate,AgentTelemetry telemetry,long agentId,String operation) implements Model {
    @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
        return Flux.defer(() -> {
            var scope = telemetry.scope(AgentContext.getIfExists().orElse(null),agentId);
            if (scope==null) return delegate.stream(messages,tools,options);
            String id = telemetry.begin(scope,operation,delegate.getModelName());
            long start = System.nanoTime(); var usage = new AtomicReference<ChatUsage>(); var done = new AtomicBoolean();
            return Flux.defer(() -> delegate.stream(messages,tools,options)).publishOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> { if (chunk.getUsage()!=null) usage.set(chunk.getUsage()); })
                    .doOnComplete(() -> { if (done.compareAndSet(false,true)) telemetry.end(id,"SUCCEEDED",start,usage.get(),null,null); })
                    .doOnError(error -> { if (done.compareAndSet(false,true)) telemetry.end(id,"FAILED",start,usage.get(),operation,error); })
                    .doFinally(signal -> { if (done.compareAndSet(false,true)) telemetry.end(id,"CANCELLED",start,usage.get(),operation,null); });
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
