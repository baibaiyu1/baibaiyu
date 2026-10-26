package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 每次调用应用服务端权限和计划开关，支持复用智能体及读取 1.x 会话。 */
public final class AgentSessionPolicy implements MiddlewareBase {
    private final boolean planningConfigured;
    private final PermissionContextState configuredPermissions;
    private final TaskReminderMiddleware taskReminder = new TaskReminderMiddleware();

    public AgentSessionPolicy(boolean planningConfigured, PermissionContextState configuredPermissions) {
        this.planningConfigured = planningConfigured;
        this.configuredPermissions = configuredPermissions;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (agent instanceof ReActAgent react) {
                PermissionContextState base = configuredPermissions;
                PermissionContextState.Builder policy = PermissionContextState.builder().mode(base.getMode());
                base.getWorkingDirectories().forEach(policy::addWorkingDirectory);
                base.getAllowRules().forEach((name, rules) -> rules.forEach(rule -> policy.addAllowRule(name, rule)));
                base.getAskRules().forEach((name, rules) -> rules.forEach(rule -> policy.addAskRule(name, rule)));
                base.getDenyRules().forEach((name, rules) -> rules.forEach(rule -> policy.addDenyRule(name, rule)));
                // SkillBox 等工具在 builder.build 时注册，仍遵循本项目的显式审批配置。
                react.getToolkit().getToolNames().stream().filter(name -> !base.getAllowRules().containsKey(name))
                        .forEach(name -> policy.addAllowRule(name,
                                new PermissionRule(name, null, PermissionBehavior.ALLOW, "gmd.configured-tool")));
                if (input.msgs() != null) for (Msg message : input.msgs()) {
                    Object results = message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
                    if (results instanceof List<?> list) for (Object result : list) {
                        if (result instanceof ConfirmResult confirmation && !confirmation.isConfirmed()
                                && confirmation.getToolCall() != null) {
                            String name = confirmation.getToolCall().getName();
                            policy.addDenyRule(name, new PermissionRule(name, null, PermissionBehavior.DENY, "gmd.denied-this-turn"));
                        }
                    }
                }
                applyPolicy(react, ctx, policy.build());
            }
            return next.apply(input);
        });
    }

    private static void applyPolicy(ReActAgent agent, RuntimeContext ctx, PermissionContextState permissions) {
        AgentStateStore store = agent.getStateStore();
        if (store == null) {
            agent.getAgentState(ctx).setPermissionContext(permissions);
            return;
        }
        String userId = ctx == null ? null : ctx.getUserId();
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) sessionId = agent.getAgentState(ctx).getSessionId();
        // onAgent 在 SDK 槽位激活之前执行，直接读取存储，避免缓存覆盖其他实例的新消息。
        for (int attempt = 0; attempt < 3; attempt++) {
            var versioned = store.getVersioned(userId, sessionId, "agent_state", AgentState.class);
            AgentState state = versioned.value();
            if (state == null) {
                agent.clearStateCache(ctx);
                state = agent.getAgentState(ctx);
            }
            if (permissions.equals(state.getPermissionContext())) return;
            state.setPermissionContext(permissions);
            long savedVersion = store.saveIfVersion(userId, sessionId, "agent_state", state, versioned.version());
            if (!store.supportsVersioning() || savedVersion != AgentStateStore.UNVERSIONED) return;
        }
        throw new IllegalStateException("会话状态正在被其他请求修改，请重试");
    }

    private boolean planningActive(RuntimeContext ctx) {
        AgentContext context = ctx == null ? null : ctx.get(AgentContext.class);
        return planningConfigured && (context == null || context.isPlanActive());
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        return planningActive(ctx) ? taskReminder.onSystemPrompt(agent, ctx, prompt) : Mono.just(prompt);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (planningActive(ctx)) return taskReminder.onReasoning(agent, ctx, input, next);
        return next.apply(new ReasoningInput(input.messages(), input.tools() == null ? null
                : input.tools().stream().filter(tool -> !"todo_write".equals(tool.getName())).toList(), input.options()));
    }
}
