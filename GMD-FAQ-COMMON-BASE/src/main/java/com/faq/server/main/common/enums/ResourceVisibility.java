package com.gmd.faq.server.main.common.enums;

import lombok.Getter;

/**
 * 资源可见性
 * <p>
 * 作用于租户内：租户之间始终隔离，可见性只决定「本组织内谁能看到」。
 * <p>
 * 口径（V23 起）：
 * <ul>
 *   <li>{@link #PRIVATE}：归属人、管理员、所属部门负责人可查看；负责人不自动取得使用或编辑权；</li>
 *   <li>{@link #TENANT}：归属人创建时的部门及其上下级部门可见，兄弟部门不可见；
 *       {@code owner_id} 为空时视为全员可见的公共资源池。</li>
 * </ul>
 * 逐账号授权可额外开放查看或使用；公开资源仅管理员可编辑，归属人可先转回私有。
 * 注意与 {@code gmd_faq_model_provider.scope_type}（GLOBAL/TENANT 表示供应商作用域）区分。
 *
 */
@Getter
public enum ResourceVisibility {

    /**
     * 私有资源；部门负责人仍可只读查看
     */
    PRIVATE("仅归属人可见"),

    /**
     * 组织共享（默认不启用，需归属人显式开启）
     */
    TENANT("部门共享（本部门及上下级部门）");

    private final String description;

    ResourceVisibility(String description) {
        this.description = description;
    }

    /**
     * 按名称解析，非法值返回 null
     */
    public static ResourceVisibility parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ResourceVisibility item : values()) {
            if (item.name().equalsIgnoreCase(name)) {
                return item;
            }
        }
        return null;
    }
}
