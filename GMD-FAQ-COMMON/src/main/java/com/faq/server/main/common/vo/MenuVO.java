package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单 VO（树形节点）
 *
 */
@Data
public class MenuVO implements SerializableEnable {

    private Long id;

    private Long parentId;

    private String name;

    private String menuType;

    private String path;

    private String component;

    private String perms;

    private String icon;

    private Integer sortOrder;

    private Boolean visible;

    private Boolean cached;

    private Boolean external;

    private Boolean status;

    private String remark;

    private LocalDateTime createdAt;

    /**
     * 子菜单
     */
    private List<MenuVO> children;

    /**
     * 按钮权限（仅菜单详情返回）
     */
    private List<MenuVO> buttons;
}
