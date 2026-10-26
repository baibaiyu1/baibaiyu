package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色表
 * <p>
 * 租户隔离（tenant_id）。内置角色（builtin=true）禁止删除。
 * <p>
 * {@link #deptId} 表达「组织（部门）级角色」：
 * <ul>
 *   <li>{@code null}：平台级角色，全平台可用（内置的 superadmin / system_admin / editor / viewer 均属此类）；</li>
 *   <li>非空：该部门（组织）的专属角色，例如「研发部主管」，只能分配给该部门（及其下级部门）内的账号。</li>
 * </ul>
 * 原 {@code gmd_faq_account_tenant.role}（拥有者/管理员/编辑者/查看者）已废弃，
 * 组织内的管理身份改由「部门负责人账号」+「部门级角色」共同表达。
 *
 */
@Getter
@Setter
@TableName(value = TableConst.ROLE)
public class Role extends BaseTenantEntity {

    /**
     * 角色名称
     */
    private String name;

    /**
     * 角色权限字符
     */
    private String code;

    /**
     * 所属部门（组织）ID；null = 平台级角色
     */
    private Long deptId;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 数据权限范围：ALL / CUSTOM / DEPT / DEPT_AND_CHILD / SELF / DEPT_AND_CHILD_OR_SELF
     */
    private String dataScope;

    /**
     * 是否内置角色（内置角色禁止删除）
     */
    private Boolean builtin;

    /**
     * 备注
     */
    private String remark;
}
