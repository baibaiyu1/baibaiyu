package com.gmd.faq.server.main.common.dto;

import lombok.Data;

/**
 * 菜单保存 DTO（新增/编辑共用一个请求体）
 *
 */
@Data
public class MenuSaveDTO {

    /**
     * 菜单ID（新增时为空）
     */
    private Long id;

    /**
     * 父菜单ID（0=根目录）
     */
    private Long parentId;

    /**
     * 菜单名称
     */
    private String name;

    /**
     * 菜单类型：DIRECTORY / MENU / BUTTON
     */
    private String menuType;

    /**
     * 路由地址
     */
    private String path;

    /**
     * 组件路径
     */
    private String component;

    /**
     * 权限字符
     */
    private String perms;

    /**
     * 菜单图标
     */
    private String icon;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 显示状态
     */
    private Boolean visible;

    /**
     * 是否缓存
     */
    private Boolean cached;

    /**
     * 是否外链
     */
    private Boolean external;

    /**
     * 菜单状态
     */
    private Boolean status;

    /**
     * 备注
     */
    private String remark;
}
