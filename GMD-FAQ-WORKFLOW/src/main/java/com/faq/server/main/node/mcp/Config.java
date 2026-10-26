package com.gmd.faq.server.main.node.mcp;

import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * MCP调用节点配置。
 *
 */
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * MCP服务ID。
     */
    private Long mcpServerId;
    /**
     * MCP工具ID。
     */
    private Long mcpToolId;
}
