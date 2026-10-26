package com.gmd.faq.server.main.engine.hook.builtins;

import com.gmd.faq.server.main.common.annotation.Scope;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.engine.hook.IAgentHook;
import io.agentscope.core.hook.HookEvent;
import com.gmd.faq.server.main.engine.tool.DelegatingToolBase;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 描述：工具确认Hook
 *
 **/
@Component
@Scope(ScopeType.GLOBAL)
public class IConfirmationHook implements IAgentHook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 注册配置由 ReActAgentHelper 转成 2.0 ASK 规则；暂停和续跑由 PermissionEngine 处理。
        return Mono.just(event);
    }

    /** 从本智能体实际工具生成原生审批规则，同名工具之间不共享确认状态。 */
    public static List<PermissionRule> rulesFor(Toolkit toolkit) {
        return toolkit.getToolNames().stream()
                .filter(name -> toolkit.getTool(name) instanceof DelegatingToolBase tool && tool.requiresConfirmation())
                .map(name -> new PermissionRule(name, null, PermissionBehavior.ASK, "gmd.tool"))
                .toList();
    }

    @Override
    public String getDescription() {
        return "人工确认钩子，配置后当需要确认的工具被调用时，用户决定是否执行";
    }
}
