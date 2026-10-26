package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import java.util.Map;
import lombok.Data;

/**
 * MCP 工具调试调用请求
 *
 */
@Data
public class McpToolDebugDTO implements SerializableEnable {

    /**
     * 工具 ID
     */
    private Long toolId;

    /**
     * 工具调用参数
     */
    private Map<String, Object> input;
}
