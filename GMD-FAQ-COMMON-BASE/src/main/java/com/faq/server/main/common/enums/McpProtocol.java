package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MCP协议
 *
 */
@Getter
@AllArgsConstructor
public enum McpProtocol {
    HTTP("HTTP"),
    SSE("SSE"),
    STDIO("StdIO");

    private final String description;
}
