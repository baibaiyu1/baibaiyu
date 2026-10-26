package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 审批流配置保存 DTO
 *
 */
@Data
public class ApprovalFlowSaveDTO {

    /**
     * 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP
     */
    private String resourceType;

    /**
     * 是否启用审批（关闭时发布申请直接生效）
     */
    private Boolean enabled;

    /**
     * 审批人账号ID列表
     */
    private List<Long> approverIds;

    /**
     * 审批人名称列表（与 approverIds 一一对应，用于列表展示）
     */
    private List<String> approverNames;

    /**
     * 备注
     */
    private String remark;
}
