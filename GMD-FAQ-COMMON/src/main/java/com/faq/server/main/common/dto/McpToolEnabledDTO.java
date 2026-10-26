package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import java.util.List;
import lombok.Data;

/**
 * MCP 工具全局启停请求
 *
 */
@Data
public class McpToolEnabledDTO implements SerializableEnable {
    private List<Long> toolIds;
    private Boolean enabled;
}
