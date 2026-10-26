package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 菜单类型
 *
 */
@Getter
@AllArgsConstructor
public enum MenuType {

    /** 目录 */
    DIRECTORY("目录"),

    /** 菜单 */
    MENU("菜单"),

    /** 按钮权限 */
    BUTTON("按钮");

    private final String description;

    /**
     * 按枚举名解析，未匹配或为空时回退到 MENU
     */
    public static MenuType of(String name) {
        if (name == null || name.isBlank()) {
            return MENU;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(MENU);
    }

    /**
     * 取中文描述
     */
    public static String describe(String name) {
        return of(name).getDescription();
    }

    /**
     * 是否为合法取值
     */
    public static boolean isValid(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return Arrays.stream(values()).anyMatch(item -> item.name().equalsIgnoreCase(name.trim()));
    }
}
