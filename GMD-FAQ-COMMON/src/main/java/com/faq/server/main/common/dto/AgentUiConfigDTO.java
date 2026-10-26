package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 智能体「功能增强 · Web App 用户体验」配置请求体
 *
 * <p>用于 {@code PUT /agent/definition/{id}/ui-config}：只提交对话端展示相关字段，
 * 支持局部更新（未传的字段保持原值）。
 *
 */
@Data
public class AgentUiConfigDTO {

    /**
     * 开场白（用户进入对话时展示的欢迎文案）
     */
    private String openingMessage;

    /**
     * 推荐问题（最多 6 条，顺序即展示顺序）
     */
    private List<String> openingQuestions;
}
