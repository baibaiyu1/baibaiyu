package com.gmd.faq.server.main.common.model;

/** 用户意图，不包含供应商参数；智能体保存完整快照以隔离模型默认值变更。 */
public record ReasoningSettings(String mode, String effort, Integer budgetTokens, boolean showThinking) {
    public static ReasoningSettings defaults() {
        return new ReasoningSettings("DEFAULT", null, null, true);
    }
}
