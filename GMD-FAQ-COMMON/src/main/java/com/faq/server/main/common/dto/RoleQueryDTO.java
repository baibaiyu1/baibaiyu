package com.gmd.faq.server.main.common.dto;

import lombok.Data;

/**
 * 角色查询 DTO
 *
 */
@Data
public class RoleQueryDTO {

    /**
     * 角色名称 / 权限字符（模糊匹配）
     */
    private String keyword;

    /**
     * 角色状态
     */
    private Boolean enabled;

    /**
     * 所属部门（组织）ID 过滤
     * <p>
     * 传入时返回「平台级角色 + 该部门的专属角色」；不传则返回全部角色。
     */
    private Long deptId;
}
