package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MCP运行模式
 *
 */
@Getter
@AllArgsConstructor
public enum McpMode {
    SYNC("同步"),
    ASYNC("异步");

    private final String description;
}
