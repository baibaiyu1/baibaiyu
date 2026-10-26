package com.gmd.faq.server.main.mcp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.McpServer;
import com.gmd.faq.server.main.common.entity.McpTool;
import com.gmd.faq.server.main.common.enums.McpActivationStatus;
import com.gmd.faq.server.main.common.enums.McpProtocol;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.vo.McpToolDebugResultVO;
import com.gmd.faq.server.main.mcp.config.McpClientConfig;
import com.gmd.faq.server.main.mcp.config.impl.HttpMcpClientConfig;
import com.gmd.faq.server.main.mcp.config.impl.SseMcpClientConfig;
import com.gmd.faq.server.main.mcp.config.impl.StdioMcpClientConfig;
import com.gmd.faq.server.main.mcp.service.McpServerService;
import com.gmd.faq.server.main.mcp.service.McpToolDebugService;
import com.gmd.faq.server.main.mcp.service.McpToolService;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * MCP 工具调试 Service 实现。
 * 复用 ToolSchemaRefresherImpl 的 try-with-resources 模式创建短生命周期客户端，
 * 调试调用不触发运行时降级，不复用运行时连接池。
 *
 */
@Service
@RequiredArgsConstructor
public class McpToolDebugServiceImpl implements McpToolDebugService {

    private static final Logger log = LoggerFactory.getLogger(McpToolDebugServiceImpl.class);

    private static final Map<McpProtocol, McpClientConfig> CLIENT_CONFIGS = Map.of(
            McpProtocol.STDIO, new StdioMcpClientConfig(),
            McpProtocol.HTTP, new HttpMcpClientConfig(),
            McpProtocol.SSE, new SseMcpClientConfig()
    );

    private final McpToolService mcpToolService;
    private final McpServerService mcpServerService;
    private final ObjectMapper objectMapper;

    @Override
    public McpToolDebugResultVO debugTool(Long toolId, Map<String, Object> input) {
        McpTool tool = mcpToolService.getById(toolId);
        if (tool == null) {
            throw new BusinessException("工具不存在");
        }

        McpServer server = mcpServerService.getById(tool.getMcpServerId());
        if (server == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        if (!Boolean.TRUE.equals(server.getEnabled())) {
            throw new BusinessException("MCP 服务已停用，无法调试");
        }
        if (server.getActivationStatus() != McpActivationStatus.ACTIVE) {
            throw new BusinessException("MCP 服务未连接，请先激活");
        }

        McpClientConfig clientConfig = CLIENT_CONFIGS.get(server.getProtocol());
        if (clientConfig == null) {
            throw new BusinessException("不支持的协议类型: " + server.getProtocol());
        }

        McpToolDebugResultVO result = new McpToolDebugResultVO();
        result.setToolName(tool.getToolName());
        result.setExecutedAt(LocalDateTime.now());

        long start = System.currentTimeMillis();
        try (McpClientWrapper wrapper = clientConfig.getMcpClient(server)) {
            Object callResult = wrapper.initialize()
                    .then(Mono.defer(() -> wrapper.callTool(tool.getToolName(), input != null ? input : Map.of())))
                    .block();
            result.setSuccess(true);
            result.setContent(objectMapper.valueToTree(callResult));
        } catch (Exception e) {
            log.warn("MCP tool debug call failed for '{}': {}", tool.getToolName(), e.getMessage());
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }
}
