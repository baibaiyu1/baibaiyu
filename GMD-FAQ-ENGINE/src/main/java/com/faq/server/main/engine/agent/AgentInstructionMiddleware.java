package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.agent.service.AgentInstructionService;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** 在推理边界注入补充要求，已执行的工具不重放。 */
@RequiredArgsConstructor
public class AgentInstructionMiddleware implements MiddlewareBase {
    private static final String SCOPE_KEY = "gmd.instructions.scope";
    private final AgentInstructionService inbox;

    @Override public int order() { return 3; }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentContext owner = ctx.get(AgentContext.class);
            if (owner == null || owner.getUserInfo() == null || owner.getAgentDefinition() == null
                    || owner.getTenantId() == null || owner.getUserInfo().getId() == null
                    || owner.getThreadId() == null || !owner.getThreadId().matches("[0-9]{1,19}")) return next.apply(input);
            var scope = new AgentInstructionService.Scope(owner.getTenantId(), owner.getUserInfo().getId(),
                    Long.parseLong(owner.getThreadId()), owner.getRunId());
            ctx.put(SCOPE_KEY, scope);
            inbox.begin(scope, owner.getAgentDefinition().getId());
            return next.apply(input)
                    .doOnNext(event -> { if (event instanceof RequireUserConfirmEvent) inbox.finish(scope, "WAITING"); })
                    .doFinally(signal -> inbox.finish(scope, "ENDED"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentInstructionService.Scope scope = ctx.get(SCOPE_KEY);
            if (scope == null) return next.apply(input);
            List<AgentInstructionService.Instruction> pending = inbox.claim(scope);
            if (pending.isEmpty()) return next.apply(input);
            List<Msg> messages = new ArrayList<>(input.messages());
            for (var instruction : pending) {
                Msg msg = Msg.builder().id("instruction-" + instruction.id()).role(MsgRole.USER)
                        .textContent("用户对当前任务的补充要求：\n" + instruction.content())
                        .metadata(Map.of("gmd_instruction_id", instruction.id())).build();
                ctx.getAgentState().contextMutable().add(msg);
                messages.add(msg);
            }
            // 应用表示加入本次推理输入；进程崩溃时不对外承诺模型已完成处理。
            var submitted = new java.util.concurrent.atomic.AtomicBoolean();
            return next.apply(new ReasoningInput(messages, input.tools(), input.options()))
                    .doOnNext(event -> {
                        if (event instanceof ModelCallStartEvent && submitted.compareAndSet(false, true)) inbox.applied(scope, pending);
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
