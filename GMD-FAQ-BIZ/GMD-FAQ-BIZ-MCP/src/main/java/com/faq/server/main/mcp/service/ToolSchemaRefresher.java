package com.gmd.faq.server.main.mcp.service;

import com.gmd.faq.server.main.common.entity.McpServer;
import com.gmd.faq.server.main.mcp.ToolSchemaRefreshResult;

/**
 * 工具目录刷新器接口，由 core 模块实现以打破 mcp 与 core 的循环依赖。
 *
 */
public interface ToolSchemaRefresher {
    ToolSchemaRefreshResult refreshToolSchemas(McpServer server);
}
