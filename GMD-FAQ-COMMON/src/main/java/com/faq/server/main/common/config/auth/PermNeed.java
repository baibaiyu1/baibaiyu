package com.gmd.faq.server.main.common.config.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述：接口所需权限点（单轨权限）
 * <p>
 * 鉴权口径：当前账号在**当前租户**下的「角色 → 菜单/按钮 perms」集合中，
 * 命中 {@link #value()} 中任意一个即通过；不再参考 {@code account_tenant.role} 旧枚举。
 * <p>
 * 权限点命名遵循 V9/V13 迁移中的 {@code module:resource:action} 约定，
 * 新增接口请同时在迁移脚本里补 perms（否则角色授权界面里无法勾选该权限）。
 *
 **/
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PermNeed {

    /**
     * 所需权限点，命中任意一个即通过
     */
    String[] value();
}
