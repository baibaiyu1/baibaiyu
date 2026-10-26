package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * MCP 工具调试调用结果
 *
 */
@Data
public class McpToolDebugResultVO implements SerializableEnable {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具返回内容（原始 MCP CallToolResult）
     */
    private Object content;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 执行时间
     */
    private LocalDateTime executedAt;
}
