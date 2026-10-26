package com.gmd.faq.server.main.engine.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** 所有项目工具装饰器共用元数据和权限委托，避免包装后丢失 SDK 的执行语义。 */
public abstract class DelegatingToolBase extends ToolBase {
    protected final AgentTool delegate;

    protected DelegatingToolBase(AgentTool delegate, Boolean readOnly, Boolean returnDirect) {
        super(metadata(delegate, readOnly, returnDirect));
        this.delegate = delegate;
    }

    private static ToolBase.Builder metadata(AgentTool tool, Boolean readOnly, Boolean returnDirect) {
        var builder = ToolBase.builder().name(tool.getName()).description(tool.getDescription())
                .inputSchema(tool.getParameters())
                .readOnly(readOnly == null ? tool.isReadOnly() : readOnly)
                .returnDirect(returnDirect == null ? tool.isReturnDirect() : returnDirect);
        if (tool instanceof ToolBase base) {
            builder.concurrencySafe(base.isConcurrencySafe()).externalTool(base.isExternalTool())
                    .stateInjected(base.isStateInjected());
            if (base.isMcp()) builder.mcp(base.getMcpName());
        }
        return builder;
    }

    @Override public Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
    public boolean requiresConfirmation() {
        return delegate instanceof DelegatingToolBase wrapped && wrapped.requiresConfirmation();
    }
    @Override public Boolean getStrict() { return delegate.getStrict(); }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return delegate instanceof ToolBase base ? base.checkPermissions(input, context) : super.checkPermissions(input, context);
    }
    @Override public boolean matchRule(String pattern, Map<String, Object> input) {
        return delegate instanceof ToolBase base ? base.matchRule(pattern, input) : super.matchRule(pattern, input);
    }
    @Override public List<PermissionRule> generateSuggestions(Map<String, Object> input) {
        return delegate instanceof ToolBase base ? base.generateSuggestions(input) : super.generateSuggestions(input);
    }
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) { return delegate.callAsync(param); }
}
