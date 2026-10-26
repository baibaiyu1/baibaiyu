package com.gmd.faq.server.main.engine.hook;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.HookConfig;
import com.gmd.faq.server.main.common.enums.CodeLanguage;
import com.gmd.faq.server.main.common.enums.HookType;
import com.gmd.faq.server.main.engine.hook.dynamices.HookInstanceLoadFactory;
import com.gmd.faq.server.main.engine.log.ChatLogHook;
import com.gmd.faq.server.main.engine.workspace.hook.ArtifactHook;
import com.gmd.faq.server.main.engine.workspace.hook.ToolResultImageHook;
import com.gmd.faq.server.main.engine.workspace.hook.WorkspaceHook;
import com.gmd.faq.server.main.engine.workspace.hook.WebsocketHook;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import com.gmd.faq.server.main.hook.service.HookConfigService;
import io.agentscope.core.hook.Hook;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述：钩子工厂
 *
 **/
@Component
@RequiredArgsConstructor
public class HooksFactory {
    private final AgentHookService agentHookService;
    private final HookConfigService hookConfigService;
    private final WebsocketHook websocketHook;
    private final ArtifactHook artifactHook;
    private final ToolResultImageHook toolResultImageHook;

    public io.agentscope.core.middleware.MiddlewareBase workspaceNotifications() {
        return new com.gmd.faq.server.main.engine.workspace.hook.WorkspaceNotificationMiddleware(websocketHook);
    }

    public List<Hook> getHooks(AgentDefinition agentDefinition) {
        List<Hook> hooks = new ArrayList<>();
        hooks.add(toolResultImageHook);

        // 配置工作空间专属Hook
        hooks.add(new WorkspaceHook());
        hooks.add(websocketHook);
        // 对话产物判定：必须在 ChatLogHook 之前，保证产物先于消息落库入队
        hooks.add(artifactHook);

        List<Long> hookIds = agentHookService.getHookIds(agentDefinition.getId());
        if (hookIds.isEmpty()) {
            // 聊天记录钩子（放到最后）
            hooks.add(new ChatLogHook());
            return hooks;
        }

        hookConfigService.listByIds(hookIds)
                .stream()
                .filter(HookConfig::getEnabled)
                .forEach(hookConfig -> {
                    Hook hook;
                    if (hookConfig.getHookType() == HookType.BUILTIN) {
                        hook = HooksRegister.getHook(hookConfig.getClassPath());
                    } else {
                        hook = HookInstanceLoadFactory.getInstanceLoader(CodeLanguage.JAVA)
                                .loadInstance(hookConfig.getCode());
                    }
                    hooks.add(hook);
                });

        // 聊天记录钩子（放到最后）
        hooks.add(new ChatLogHook());
        return hooks;
    }
}
