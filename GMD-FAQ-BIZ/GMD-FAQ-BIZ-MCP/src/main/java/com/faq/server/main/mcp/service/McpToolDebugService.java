package com.gmd.faq.server.main.mcp.service;

import com.gmd.faq.server.main.common.vo.McpToolDebugResultVO;
import java.util.Map;

/**
 * MCP 工具调试 Service
 *
 */
public interface McpToolDebugService {

    /**
     * 调试调用 MCP 工具
     *
     * @param toolId 工具 ID
     * @param input  工具调用参数
     * @return 调试结果
     */
    McpToolDebugResultVO debugTool(Long toolId, Map<String, Object> input);
}
