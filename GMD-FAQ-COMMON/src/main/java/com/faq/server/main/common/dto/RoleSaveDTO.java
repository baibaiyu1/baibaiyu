package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色保存 DTO（新增/编辑共用一个请求体）
 *
 */
@Data
public class RoleSaveDTO {

    /**
     * 角色ID（新增时为空）
     */
    private Long id;

    /**
     * 角色名称
     */
    private String name;

    /**
     * 角色权限字符
     */
    private String code;

    /**
     * 所属部门（组织）ID；留空或为 0 表示平台级角色（全平台可用）
     */
    private Long deptId;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 角色状态
     */
    private Boolean enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 数据权限范围：ALL / CUSTOM / DEPT / DEPT_AND_CHILD / SELF / DEPT_AND_CHILD_OR_SELF
     */
    private String dataScope;

    /**
     * 勾选的菜单权限ID集合（含目录、菜单、按钮）
     */
    private List<Long> menuIds;
}
