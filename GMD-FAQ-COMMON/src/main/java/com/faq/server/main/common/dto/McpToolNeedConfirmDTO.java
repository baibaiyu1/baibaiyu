package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import java.util.List;
import lombok.Data;

/**
 * MCP 工具批量设置「需要人工确认」请求
 *
 */
@Data
public class McpToolNeedConfirmDTO implements SerializableEnable {
    private List<Long> toolIds;
    private Boolean needConfirm;
}
