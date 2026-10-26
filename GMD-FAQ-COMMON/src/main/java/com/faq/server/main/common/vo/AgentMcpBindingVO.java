package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.enums.McpToolExposureMode;
import java.util.List;
import lombok.Data;

/**
 * Agent 与 MCP 的绑定信息
 *
 */
@Data
public class AgentMcpBindingVO implements SerializableEnable {
    private Long mcpServerId;
    private McpToolExposureMode exposureMode;
    private List<Long> mcpToolIds;
}
