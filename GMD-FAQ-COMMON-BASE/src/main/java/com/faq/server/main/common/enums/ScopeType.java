package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 内置组件作用域类型
 *
 */
@Getter
@AllArgsConstructor
public enum ScopeType {
    /** 全局可用：所有租户自动获得一份副本 */
    GLOBAL("全局"),
    /** 指定租户：仅注入到指定租户 */
    TENANT("指定租户");

    private final String description;
}
