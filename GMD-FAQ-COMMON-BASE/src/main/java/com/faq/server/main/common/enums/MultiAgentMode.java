package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 多智能体模式
 *
 */
@Getter
@AllArgsConstructor
public enum MultiAgentMode {
    PIPELINE("管道"),
    MSG_HUB("MsgHub"),
    AGENT_AS_TOOL("Agent as Tool"),
    DEBATE("多智能体辩论");

    private final String description;
}
