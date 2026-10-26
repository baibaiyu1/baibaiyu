package com.gmd.faq.server.main.common.dto;

import lombok.Data;

/**
 * 审批申请单查询 DTO
 *
 */
@Data
public class ApprovalRequestQueryDTO {

    /**
     * 查询范围：pending=待我审批，mine=我发起的，all=全部（默认 mine）
     */
    private String scope;

    /**
     * 状态：PENDING/APPROVED/REJECTED/CANCELED，为空表示不限
     */
    private String status;

    /**
     * 资源类型，为空表示不限
     */
    private String resourceType;

    /**
     * 资源名称 / 申请人关键字（模糊匹配）
     */
    private String keyword;
}
