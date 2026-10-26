package com.gmd.faq.server.main.common.dto;

import lombok.Data;

/**
 * 提交发布审批 DTO
 *
 */
@Data
public class ApprovalSubmitDTO {

    /**
     * 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP
     */
    private String resourceType;

    /**
     * 资源ID
     */
    private String resourceId;

    /**
     * 资源名称（用于列表展示，前端可传）
     */
    private String resourceName;

    /**
     * 资源版本
     */
    private String resourceVersion;

    /**
     * 申请说明
     */
    private String remark;
}
