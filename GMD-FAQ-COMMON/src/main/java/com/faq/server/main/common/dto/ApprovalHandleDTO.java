package com.gmd.faq.server.main.common.dto;

import lombok.Data;

/**
 * 审批处理（通过 / 拒绝）DTO
 *
 */
@Data
public class ApprovalHandleDTO {

    /**
     * 审批意见
     */
    private String opinion;
}
