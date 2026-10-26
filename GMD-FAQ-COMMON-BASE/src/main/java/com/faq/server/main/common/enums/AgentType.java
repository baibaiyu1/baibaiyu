package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 描述：智能体类型
 *
 **/
@Getter
@AllArgsConstructor
public enum AgentType {
    CUSTOM("自定义"),
    A2A("A2A");

    private final String description;
}
