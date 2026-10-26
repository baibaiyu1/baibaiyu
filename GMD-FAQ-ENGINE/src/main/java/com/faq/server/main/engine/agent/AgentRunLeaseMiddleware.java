package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.agent.service.AgentConversationQueue;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.*;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 旧进程失去租约后不能开始新的推理或工具执行。 */
public record AgentRunLeaseMiddleware(AgentConversationQueue queue) implements MiddlewareBase {
    private void check(RuntimeContext ctx) {
        AgentContext owner = ctx.get(AgentContext.class);
        if (owner == null || owner.getRunOwnerId() == null || !owner.getThreadId().matches("[0-9]{1,19}")) return;
        var scope = queue.sessionScope(owner.getTenantId(),Long.parseLong(owner.getThreadId()));
        if (!queue.owns(scope,owner.getRunId(),owner.getRunOwnerId())) throw new IllegalStateException("运行占用已失效，已停止后续操作");
    }
    @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput,Flux<AgentEvent>> next) {
        return Flux.defer(() -> { check(ctx); return next.apply(input); });
    }
    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput,Flux<AgentEvent>> next) {
        return Flux.defer(() -> { check(ctx); return next.apply(input); });
    }
}
