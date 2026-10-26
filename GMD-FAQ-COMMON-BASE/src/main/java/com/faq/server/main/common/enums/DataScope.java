package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 角色数据权限范围
 * <p>
 * 与「系统设置 - 角色配置 - 分配数据权限」弹窗中的下拉选项一一对应。
 *
 */
@Getter
@AllArgsConstructor
public enum DataScope {

    /** 全部数据权限 */
    ALL("全部数据权限"),

    /** 自定义数据权限 */
    CUSTOM("自定义数据权限"),

    /** 本部门数据权限 */
    DEPT("本部门数据权限"),

    /** 本部门及以下数据权限 */
    DEPT_AND_CHILD("本部门及以下数据权限"),

    /** 仅本人数据权限 */
    SELF("仅本人数据权限"),

    /** 部门及以下或本人数据权限 */
    DEPT_AND_CHILD_OR_SELF("部门及以下或本人数据权限");

    private final String description;

    /**
     * 按枚举名解析，未匹配或为空时回退到 ALL
     */
    public static DataScope of(String name) {
        if (name == null || name.isBlank()) {
            return ALL;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(ALL);
    }

    /**
     * 取中文描述，未匹配或为空时回退到 ALL 的描述
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

    /**
     * 多角色合并：取「最宽」的数据范围
     * <p>
     * 一个账号可以同时拥有多个角色（如 superadmin + viewer），按最宽的那个生效，
     * 与「权限点取并集」的口径保持一致。
     * <p>
     * 非法/空值按 {@link #SELF} 处理（最小权限），**不用** {@link #of(String)}——
     * 那个方法未匹配时回退 ALL，用在鉴权上会放大权限。
     *
     * @param names 角色上的 data_scope 取值集合
     * @return 合并后的数据范围；集合为空时返回 SELF
     */
    public static DataScope widest(java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return SELF;
        }
        DataScope result = null;
        for (String name : names) {
            DataScope item = isValid(name) ? valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)) : SELF;
            if (result == null || item.width() > result.width()) {
                result = item;
            }
        }
        return result == null ? SELF : result;
    }

    /**
     * 范围宽度（越大越宽），仅用于多角色合并比较
     * <p>
     * CUSTOM（自定义部门）当前没有角色-部门映射表，无法落地，按最小权限处理，故排在最后。
     */
    private int width() {
        return switch (this) {
            case ALL -> 6;
            case DEPT_AND_CHILD_OR_SELF -> 5;
            case DEPT_AND_CHILD -> 4;
            case DEPT -> 3;
            case SELF -> 2;
            case CUSTOM -> 1;
        };
    }
}
