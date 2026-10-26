package com.gmd.faq.server.main.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批申请单 VO
 *
 */
@Data
public class ApprovalRequestVO {

    /**
     * 主键
     */
    private Long id;

    /**
     * 资源类型
     */
    private String resourceType;

    /**
     * 资源类型名称
     */
    private String resourceTypeDesc;

    /**
     * 资源ID
     */
    private String resourceId;

    /**
     * 资源名称
     */
    private String resourceName;

    /**
     * 资源版本
     */
    private String resourceVersion;

    /**
     * 申请人账号ID
     */
    private Long applicantId;

    /**
     * 申请人名称
     */
    private String applicantName;

    /**
     * 处理人账号ID
     */
    private Long approverId;

    /**
     * 处理人名称
     */
    private String approverName;

    /**
     * 状态
     */
    private String status;

    /**
     * 状态名称
     */
    private String statusDesc;

    /**
     * 审批意见
     */
    private String opinion;

    /**
     * 申请说明
     */
    private String remark;

    /**
     * 是否为当前登录用户可处理（待审批且本人为审批人）
     */
    private Boolean handleable;

    /**
     * 是否为当前登录用户发起
     */
    private Boolean mine;

    /**
     * 申请时间
     */
    private LocalDateTime createdAt;

    /**
     * 处理时间
     */
    private LocalDateTime handledAt;
}
