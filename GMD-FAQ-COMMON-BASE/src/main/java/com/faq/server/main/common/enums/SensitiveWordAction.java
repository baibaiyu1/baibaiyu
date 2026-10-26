package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 敏感词处理动作
 *
 */
@Getter
@AllArgsConstructor
public enum SensitiveWordAction {
    BLOCK("阻断"),
    REPLACE("替换"),
    WARN("警告");

    private final String description;
}
