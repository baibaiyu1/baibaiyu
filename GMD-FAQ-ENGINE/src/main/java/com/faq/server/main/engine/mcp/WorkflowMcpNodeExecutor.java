package com.gmd.faq.server.main.engine.mcp;

import com.gmd.faq.server.main.common.vo.McpToolDebugResultVO;
import com.gmd.faq.server.main.mcp.service.McpToolDebugService;
import com.gmd.faq.server.main.node.mcp.McpNodeExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工作流MCP节点执行器。
 * 复用 McpToolDebugService 的短生命周期客户端模式调用MCP工具。
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowMcpNodeExecutor implements McpNodeExecutor {

    private final McpToolDebugService mcpToolDebugService;

    @Override
    public McpToolDebugResultVO execute(Long toolId, Map<String, Object> params) {
        return mcpToolDebugService.debugTool(toolId, params);
    }
}
