package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Hook类型
 *
 */
@Getter
@AllArgsConstructor
public enum HookType {
    BUILTIN("内置"),
    CUSTOM("自定义");

    private final String description;
}
