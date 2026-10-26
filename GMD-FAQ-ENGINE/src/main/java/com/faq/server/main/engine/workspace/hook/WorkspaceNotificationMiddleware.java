package com.gmd.faq.server.main.engine.workspace.hook;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/** 下一模型调用或任务结束才通知，确保旧 PostActing Hook 已完成图片、产物及日志处理。 */
@RequiredArgsConstructor
public final class WorkspaceNotificationMiddleware implements MiddlewareBase {
    private static final String PENDING = "gmd.workspace.notification.pending";
    private final WebsocketHook notifications;

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentContext owner = context.get(AgentContext.class);
            if (owner == null || owner.getThreadId() == null) return next.apply(input);
            AtomicBoolean changed = new AtomicBoolean();
            context.put(PENDING, changed);
            Runnable flush = () -> {
                if (changed.getAndSet(false)) notifications.sendFileChangeWs(owner.getThreadId(), null);
            };
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ModelCallStartEvent || event instanceof AgentResultEvent || event instanceof AgentEndEvent) flush.run();
            }).doOnComplete(flush);
        });
    }

    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AtomicBoolean changed = context.get(PENDING);
            if (changed == null) return next.apply(input);
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ToolResultEndEvent result
                        && ToolConstants.WRITE_TOOLS.contains(result.getToolCallName())
                        && (result.getState() == ToolResultState.SUCCESS || result.getState() == ToolResultState.ERROR)) {
                    changed.set(true);
                }
            });
        });
    }
}
