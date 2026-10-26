package com.gmd.faq.server.main.common.enums.workflow;

/**
 * 描述：流程状态
 *
 **/
public enum WorkflowStatus {
    PUBLISHED("已发布，已生成历史版本"),
    DRAFT("草稿未生成历史版本");
    WorkflowStatus(String desc) {}
}
