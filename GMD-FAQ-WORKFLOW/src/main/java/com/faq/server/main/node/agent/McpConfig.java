package com.gmd.faq.server.main.node.agent;

import com.gmd.faq.server.main.common.enums.McpToolExposureMode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体节点MCP绑定配置。
 *
 */
@Getter
@Setter
public class McpConfig {
    /**
     * MCP服务ID。
     */
    private Long mcpServerId;
    /**
     * 工具暴露模式。
     */
    private McpToolExposureMode exposureMode = McpToolExposureMode.ALL_GLOBAL;
    /**
     * 局部选择的MCP工具ID列表。
     */
    private List<Long> mcpToolIds = new ArrayList<>();
}
