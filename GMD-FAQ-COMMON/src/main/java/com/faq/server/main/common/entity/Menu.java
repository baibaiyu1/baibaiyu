package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单表
 * <p>
 * 全局数据（不按租户隔离）。自关联树结构：parent_id = 0 表示根目录。
 * menu_type 取值：DIRECTORY（目录）/ MENU（菜单）/ BUTTON（按钮权限）。
 *
 */
@Getter
@Setter
@TableName(value = TableConst.MENU)
public class Menu {

    /**
     * 主键
     */
    @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.ASSIGN_ID)
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
     * 菜单状态（true=正常 false=停用）
     */
    private Boolean status;

    /**
     * 备注
     */
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 子菜单（仅树形查询时填充，不落库）
     */
    @TableField(exist = false)
    private List<Menu> children;

    /**
     * 该菜单下的按钮权限（仅菜单详情查询时填充，不落库）
     */
    @TableField(exist = false)
    private List<Menu> buttons;
}
