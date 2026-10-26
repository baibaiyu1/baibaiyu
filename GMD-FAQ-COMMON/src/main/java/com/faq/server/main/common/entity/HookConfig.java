package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.HookType;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * Hook配置表
 *
 */
@Getter
@Setter
@TableName(value = TableConst.HOOK)
public class HookConfig extends BaseTenantEntity {

    /**
     * Hook名称
     */
    private String name;

    /**
     * Hook类型
     */
    private HookType hookType;

    /**
     * 描述
     */
    private String description;

    /**
     * hook路径（tool_type为SYSTEM时使用）
     */
    private String classPath;

    /**
     * hook内容（tool_type为CUSTOM时使用）
     */
    private String code;

    /**
     * 执行优先级
     */
    private Integer priority;

    /**
     * 作用域类型: GLOBAL=全局, TENANT=指定租户
     */
    private ScopeType scopeType;
}
