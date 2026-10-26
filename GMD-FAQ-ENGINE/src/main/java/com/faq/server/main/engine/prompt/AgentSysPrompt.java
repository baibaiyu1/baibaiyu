package com.gmd.faq.server.main.engine.prompt;

import com.gmd.faq.server.main.common.entity.AgentDefinition;

/**
 * 描述：agent 系统提示词
 *
 **/
public interface AgentSysPrompt {
    String getPrompt(AgentDefinition agentDefinition);

    /**
     * 获取顺序（越大优先级越高）
     *
     * @return 顺序
     */
    int order();
}
