package com.gmd.faq.server.main.common.annotation;

import com.gmd.faq.server.main.common.enums.ScopeType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内置组件作用域注解
 * <p>
 * 标注在 IAgentTool / IAgentHook 实现类上，用于声明该内置组件的租户归属：
 * <ul>
 *   <li>GLOBAL：所有租户自动获得一份副本</li>
 *   <li>TENANT：仅注入到 tenantCodes 指定的租户</li>
 * </ul>
 * 未标注时默认为 GLOBAL（向后兼容）。
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Scope {

    /**
     * 作用域类型
     */
    ScopeType value() default ScopeType.GLOBAL;

    /**
     * 目标租户编码列表（仅在 value = TENANT 时生效）
     */
    String[] tenantCodes() default {};
}
