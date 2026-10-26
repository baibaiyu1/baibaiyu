package com.gmd.faq.server.main.engine.plan;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.tool.ProjectToolkit;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.middleware.PlanModeMiddleware;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 将原生 Plan Mode 组合进既有 ReAct，保留现有状态、RAG、压缩、技能和事件路径。 */
public final class AgentPlanModeMiddleware extends PlanModeMiddleware {
    private static final String PREPARED = "gmd.plan.request.prepared";
    private static final String DENIED_EVENTS = "gmd.plan.denied.events";
    private final AgentPlanService plans;
    private final PlanModeManager manager;
    private final Toolkit toolkit;

    public AgentPlanModeMiddleware(AgentPlanService plans, PlanModeManager manager, Toolkit toolkit) {
        super(manager, name -> toolkit instanceof ProjectToolkit project && project.isTrustedRead(toolkit.getTool(name)));
        this.plans = plans;
        this.manager = manager;
        this.toolkit = toolkit;
    }

    private boolean prepare(Agent agent, RuntimeContext context) {
        AgentContext owner = context.get(AgentContext.class);
        if (!AgentPlanService.available(owner)) return false;
        if (!Boolean.TRUE.equals(context.get(PREPARED))) {
            context.put(PREPARED, true);
            if (owner.isPlanMode()) plans.enter(owner);
        }
        AgentState state = RuntimeContext.resolveAgentState(context, agent);
        var document = plans.snapshot(owner);
        if (document.readOnly()) manager.enter(state);
        else state.getPlanModeContext().setPlanActive(false);
        if (!"NONE".equals(document.status())) state.getPlanModeContext().setCurrentPlanFile(AgentPlanService.PATH);
        // ReAct builder 隔离复制工具注册表，必须保护实际执行的副本。
        Toolkit active = toolkitOf(agent);
        for (String name : active.getToolNames()) {
            AgentTool tool = active.getTool(name);
            if (tool instanceof PlanGuardedTool) continue;
            boolean safe = toolkit instanceof ProjectToolkit project && project.isTrustedRead(tool);
            boolean control = toolkit instanceof ProjectToolkit project && project.isControl(tool);
            active.registerAgentTool(new PlanGuardedTool(tool, plans, safe, control));
        }
        return document.readOnly();
    }

    private Toolkit toolkitOf(Agent agent) { return agent instanceof ReActAgent react ? react.getToolkit() : toolkit; }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            Sinks.Many<AgentEvent> denied = Sinks.many().unicast().onBackpressureBuffer();
            ctx.put(DENIED_EVENTS, denied);
            // SDK acting 只发布核心执行事件；中间件合成的拒绝事件经本轮独立通道送到 AG-UI。
            return Flux.merge(denied.asFlux(), next.apply(input)
                    .doFinally(signal -> denied.tryEmitComplete()));
        });
    }

    @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        return Mono.defer(() -> {
            prepare(agent, ctx);
            return super.onSystemPrompt(agent, ctx, prompt);
        });
    }

    @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                                Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            boolean readOnly = prepare(agent, ctx);
            boolean available = AgentPlanService.available(ctx.get(AgentContext.class));
            var tools = input.tools() == null ? null : input.tools().stream().filter(tool -> {
                String name = tool.getName();
                if ((!available || !com.gmd.faq.server.main.common.util.UserUtils.isAdmin()
                        && !com.gmd.faq.server.main.common.util.UserUtils.hasPerm("agent:chat:instruct"))
                        && name.startsWith("plan_")) return false;
                AgentTool registered = toolkitOf(agent).getTool(name);
                return !readOnly || registered != null && (registered.isReadOnly()
                        || registered instanceof PlanGuardedTool guarded && guarded.isPlanControl());
            }).toList();
            return next.apply(new ReasoningInput(input.messages(), tools, input.options()));
        });
    }

    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                             Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            prepare(agent, ctx);
            AgentContext owner = ctx.get(AgentContext.class);
            if (!AgentPlanService.available(owner)) return next.apply(input);
            boolean updatesPlan = input.toolCalls().stream().anyMatch(call -> "plan_enter".equals(call.getName()) || "plan_write".equals(call.getName()));
            if (updatesPlan) {
                plans.enter(owner);
                manager.enter(RuntimeContext.resolveAgentState(ctx, agent));
            }
            boolean readOnly = plans.readOnly(owner), approvalRequested = false;
            List<ToolUseBlock> accepted = new ArrayList<>(), rejected = new ArrayList<>();
            for (ToolUseBlock call : input.toolCalls()) {
                AgentTool registered = toolkitOf(agent).getTool(call.getName());
                boolean control = registered instanceof PlanGuardedTool guarded && guarded.isPlanControl();
                if (readOnly && (registered == null || !registered.isReadOnly() && !control)) {
                    rejected.add(call);
                } else if (control && "plan_exit".equals(call.getName())) {
                    // 同批写方案与申请审批不能绑定到尚未落盘或旧版本的内容。
                    if (updatesPlan || approvalRequested
                            || RuntimeContext.resolveAgentState(ctx, agent).getPermissionContext().getDenyRules().containsKey("plan_exit")
                            || !plans.approvedFor(owner, call.getId())
                            && !plans.requestApproval(owner, call.getId())) rejected.add(call);
                    else { accepted.add(call); approvalRequested = true; }
                } else accepted.add(call);
            }
            Flux<AgentEvent> denied = deny(agent, ctx, rejected);
            return accepted.isEmpty() ? denied
                    : denied.concatWith(super.onActing(agent, ctx, new ActingInput(accepted), next));
        });
    }

    private Flux<AgentEvent> deny(Agent agent, RuntimeContext context, List<ToolUseBlock> calls) {
        AgentState state = RuntimeContext.resolveAgentState(context, agent);
        List<AgentEvent> events = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            if (state.getContext().stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .anyMatch(result -> call.getId().equals(result.getId()))) continue;
            String text = "方案尚未批准，只允许已授权的只读调查和方案工具。请先单独使用 plan_write 保存完整方案，再用一次 plan_exit 请求审批。";
            ToolResultBlock result = ToolResultBlock.text(text).withIdAndName(call.getId(), call.getName()).withState(ToolResultState.DENIED);
            state.contextMutable().add(ToolResultMessageBuilder.buildToolResultMsg(result, call, agent.getName()));
            events.add(new ToolResultStartEvent(state.getReplyId(), call.getId(), call.getName()));
            events.add(new ToolResultTextDeltaEvent(state.getReplyId(), call.getId(), call.getName(), text));
            events.add(new ToolResultEndEvent(state.getReplyId(), call.getId(), call.getName(), ToolResultState.DENIED));
        }
        Sinks.Many<AgentEvent> sink = context.get(DENIED_EVENTS);
        if (sink != null) events.forEach(sink::tryEmitNext);
        return Flux.fromIterable(events);
    }
}
