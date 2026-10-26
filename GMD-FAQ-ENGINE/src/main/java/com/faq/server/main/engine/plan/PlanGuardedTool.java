package com.gmd.faq.server.main.engine.plan;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import java.util.Set;
import com.gmd.faq.server.main.engine.tool.DelegatingToolBase;
import reactor.core.publisher.Mono;

/** 原生方案中间件之外的逐工具校验，覆盖 SDK 默认放行的委派工具及直接调用。 */
public final class PlanGuardedTool extends DelegatingToolBase {
    public static final Set<String> CONTROL = Set.of("plan_enter", "plan_write", "plan_exit", "todo_write");
    private final AgentPlanService plans;
    private final boolean control;

    public PlanGuardedTool(AgentTool delegate, AgentPlanService plans, boolean trustedRead, boolean control) {
        super(delegate, trustedRead, null);
        this.plans = plans;
        this.control = control;
    }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            AgentContext owner = param.getRuntimeContext() == null ? null : param.getRuntimeContext().get(AgentContext.class);
            if (!AgentPlanService.available(owner)) {
                if (getName().startsWith("plan_")) return denied(param, "方案模式仅支持登录账号的本人正式会话");
                return delegate.callAsync(param);
            }
            if (control && "plan_enter".equals(getName())) plans.enter(owner);
            if (control && "plan_exit".equals(getName()) && !plans.approvedFor(owner, param.getToolUseBlock().getId()))
                return denied(param, "当前方案没有有效批准，仍保持只读。请先保存方案并请求审批。");
            if (plans.readOnly(owner) && !isReadOnly() && !control)
                return denied(param, "方案尚未批准，只允许只读调查和修改方案；不能执行写工具、代码、工作流、MCP 或委派。");
            return delegate.callAsync(param);
        });
    }
    public boolean isPlanControl() { return control; }

    private Mono<ToolResultBlock> denied(ToolCallParam param, String message) {
        return Mono.just(ToolResultBlock.text(message).withIdAndName(param.getToolUseBlock().getId(), getName())
                .withState(ToolResultState.DENIED));
    }
}
