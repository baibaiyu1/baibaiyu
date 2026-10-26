package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 租户内角色
 *
 */
@Getter
@AllArgsConstructor
public enum TenantRole {
    TENANT_OWNER("拥有者"),
    TENANT_ADMIN("管理员"),
    TENANT_EDITOR("编辑者"),
    TENANT_VIEWER("查看者");

    private final String description;
}
