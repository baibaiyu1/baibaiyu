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
    private final String remoteToolName;

    public LazyMcpAgentTool(RuntimeDegradeContext degradeContext,
                            McpSchema.Tool toolSchema,
                            Supplier<Mono<McpClientWrapper>> initializedClientSupplier,
                            McpRuntimeDegradeService mcpRuntimeDegradeService) {
        super(ToolBase.builder().name(runtimeName(degradeContext.serverId(), toolSchema.name()))
                .description(toolSchema.description() == null ? "" : toolSchema.description())
                .inputSchema(McpTool.convertMcpSchemaToParameters(toolSchema.inputSchema(), Set.of()))
                .mcp(degradeContext.serverName()));
        this.degradeContext = degradeContext;
        this.remoteToolName = toolSchema.name();
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
                    .flatMap(client -> client.callTool(remoteToolName, param.getInput()))
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

    /** 稳定且符合供应商工具名长度限制；远端调用仍使用服务自己的原名。 */
    static String runtimeName(Long serverId, String toolName) {
        if (serverId == null || toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("MCP 服务和工具名称不能为空");
        String prefix = "mcp_" + serverId + "_";
        String safe = toolName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.equals(toolName) && prefix.length() + safe.length() <= 64) return prefix + safe;
        String hash = org.springframework.util.DigestUtils.md5DigestAsHex(toolName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int available = 64 - prefix.length() - hash.length() - 1;
        return prefix + safe.substring(0, Math.min(safe.length(), Math.max(0, available))) + "_" + hash;
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
