package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色 VO
 *
 */
@Data
public class RoleVO implements SerializableEnable {

    private Long id;

    private String name;

    private String code;

    /**
     * 所属部门（组织）ID；null = 平台级角色
     */
    private Long deptId;

    /**
     * 所属部门（组织）名称；平台级角色为空
     */
    private String deptName;

    private Integer sortOrder;

    private String dataScope;

    /** 数据权限范围的中文描述，便于前端直接展示 */
    private String dataScopeLabel;

    private Boolean builtin;

    private String remark;

    private Boolean enabled;

    private LocalDateTime createdAt;

    /**
     * 已勾选的菜单/按钮权限ID集合
     */
    private List<Long> menuIds;

    /**
     * 已分配用户数
     */
    private Long userCount;
}
