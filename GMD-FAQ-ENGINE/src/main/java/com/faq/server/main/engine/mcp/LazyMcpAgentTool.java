package com.gmd.faq.server.main.engine.mcp;

import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.agui.AgentContextPropagation;
import com.gmd.faq.server.main.mcp.service.McpRuntimeDegradeService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 基于缓存工具目录注册的懒加载 MCP 工具。
 */
public class LazyMcpAgentTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(LazyMcpAgentTool.class);

    private final RuntimeDegradeContext degradeContext;
    private final Supplier<Mono<McpClientWrapper>> initializedClientSupplier;
    private final McpRuntimeDegradeService mcpRuntimeDegradeService;
    private final Map<String, Object> outputSchema;

    public LazyMcpAgentTool(RuntimeDegradeContext degradeContext,
                            McpSchema.Tool toolSchema,
                            Supplier<Mono<McpClientWrapper>> initializedClientSupplier,
                            McpRuntimeDegradeService mcpRuntimeDegradeService) {
        super(ToolBase.builder().name(toolSchema.name())
                .description(toolSchema.description() == null ? "" : toolSchema.description())
                .inputSchema(McpTool.convertMcpSchemaToParameters(toolSchema.inputSchema(), Set.of()))
                .mcp(degradeContext.serverName()));
        this.degradeContext = degradeContext;
        this.initializedClientSupplier = initializedClientSupplier;
        this.mcpRuntimeDegradeService = mcpRuntimeDegradeService;
        this.outputSchema = toolSchema.outputSchema() != null
                ? new HashMap<>(toolSchema.outputSchema())
                : null;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentContext agentContext = param.getContext().get(AgentContext.class);
        if (agentContext == null) {
            return Mono.just(ToolResultBlock.error(unavailableMessage(new Exception("AgentContext is null"))));
        }

        // 保存租户信息到局部变量
        Long tenantId = agentContext.getTenantId();
        String tenantCode = agentContext.getTenantCode();

        // 使用 defer 确保整个执行链都在租户上下文中
        return Mono.defer(() -> {
            return initializedClientSupplier.get()
                    .flatMap(client -> client.callTool(getName(), param.getInput()))
                    .doOnSuccess(result -> {
                        mcpRuntimeDegradeService.recordSuccess(
                                degradeContext.serverId(),
                                degradeContext.activationRevision(),
                                degradeContext.configHash(),
                                degradeContext.runtimeFailThreshold(),
                                tenantId);
                    })
                    .map(McpToolResultConverter::convert)
                    .onErrorResume(e -> {
                        mcpRuntimeDegradeService.recordFailure(
                                degradeContext.serverId(),
                                degradeContext.activationRevision(),
                                degradeContext.configHash(),
                                degradeContext.runtimeFailThreshold(),
                                e,
                                tenantId);
                        log.warn("MCP tool '{}' from '{}' unavailable: {}",
                                getName(), degradeContext.serverName(), e.getMessage());
                        return Mono.just(ToolResultBlock.error(unavailableMessage(e)));
                    })
                    ;
        }).contextWrite(context -> AgentContextPropagation.withContext(context, agentContext));
    }

    private String unavailableMessage(Throwable e) {
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "MCP service '" + degradeContext.serverName() + "' is unavailable. Tool '" + getName()
                + "' cannot be used right now. Reason: " + reason;
    }

    /**
     * 运行时自动降级上下文快照。
     */
    public record RuntimeDegradeContext(Long serverId,
                                        String serverName,
                                        Long activationRevision,
                                        String configHash,
                                        Integer runtimeFailThreshold) {
    }
}
