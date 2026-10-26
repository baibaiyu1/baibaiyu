package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建会话 DTO
 *
 */
@Getter
@Setter
public class ChatSessionCreateDTO implements SerializableEnable {
    /**
     * 智能体ID
     */
    private Long agentId;
    /**
     * 会话标题，可选
     */
    private String title;
    /**
     * 是否初始化工作区
     */
    private Boolean initWorkspace;
}
