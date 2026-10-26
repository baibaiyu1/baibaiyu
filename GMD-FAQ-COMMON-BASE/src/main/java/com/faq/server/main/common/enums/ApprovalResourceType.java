package com.gmd.faq.server.main.common.enums;

import lombok.Getter;

/**
 * 可审批的资源类型
 * <p>
 * 用于审批流配置与审批申请单的资源类型标识，与各业务表一一对应。
 *
 */
@Getter
public enum ApprovalResourceType {

    /**
     * 智能体（gmd_faq_agent_definition）
     */
    AGENT("智能体"),

    /**
     * 工作流（gmd_faq_workflow）
     */
    WORKFLOW("工作流"),

    /**
     * 技能包（gmd_faq_skill_package）
     */
    SKILL("技能包"),

    /**
     * 工具（gmd_faq_tool_config）
     */
    TOOL("工具"),

    /**
     * 知识库（gmd_faq_knowledge_base_config）
     */
    KNOWLEDGE("知识库"),

    /**
     * MCP 服务（gmd_faq_mcp_server）
     */
    MCP("MCP 服务");

    private final String description;

    ApprovalResourceType(String description) {
        this.description = description;
    }

    /**
     * 按名称解析，非法值返回 null
     */
    public static ApprovalResourceType parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ApprovalResourceType item : values()) {
            if (item.name().equalsIgnoreCase(name)) {
                return item;
            }
        }
        return null;
    }
}
