package com.gmd.faq.server.main.engine.tool;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.common.consts.SysConst;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

/** 让旧 AgentTool 参与 2.0 权限检查；原执行逻辑及模式保持不变。 */
public final class PermissionAwareTool extends DelegatingToolBase {
    private static final ToolResultImageSupport DIRECT_IMAGES = new ToolResultImageSupport();
    private final boolean confirmationRequired;
    public PermissionAwareTool(AgentTool delegate) {
        this(delegate, null);
    }
    public PermissionAwareTool(AgentTool delegate, Boolean returnDirect) {
        this(delegate, returnDirect, false);
    }
    public PermissionAwareTool(AgentTool delegate, Boolean returnDirect, boolean confirmationRequired) {
        super(delegate, null, returnDirect);
        this.confirmationRequired = confirmationRequired;
    }
    @Override public boolean requiresConfirmation() { return confirmationRequired || super.requiresConfirmation(); }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param).map(result -> {
            if (!isReturnDirect()) return result;
            AgentContext owner = param.getRuntimeContext() == null ? null : param.getRuntimeContext().get(AgentContext.class);
            if (owner == null || owner.getTenantCode() == null || owner.getThreadId() == null
                    || !owner.getTenantCode().matches("[A-Za-z0-9_-]+") || !owner.getThreadId().matches("[A-Za-z0-9_-]+")) return result;
            // 原生直接返回会忽略 PostActing 改写，图片必须在工具返回边界归一化。
            return DIRECT_IMAGES.normalize(result, Path.of(SysConst.getWorkspacePath(owner.getTenantCode()), owner.getThreadId()));
        });
    }
}
