package com.gmd.faq.server.main.common.enums;

import lombok.Getter;

/**
 * 审批状态
 *
 */
@Getter
public enum ApprovalStatus {

    /**
     * 待审批
     */
    PENDING("待审批"),

    /**
     * 已通过（资源随之生效）
     */
    APPROVED("已通过"),

    /**
     * 已拒绝
     */
    REJECTED("已拒绝"),

    /**
     * 申请人已撤回
     */
    CANCELED("已取消");

    private final String description;

    ApprovalStatus(String description) {
        this.description = description;
    }

    /**
     * 按名称解析，非法值返回 null（避免脏数据触发异常）
     */
    public static ApprovalStatus parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ApprovalStatus item : values()) {
            if (item.name().equalsIgnoreCase(name)) {
                return item;
            }
        }
        return null;
    }
}
