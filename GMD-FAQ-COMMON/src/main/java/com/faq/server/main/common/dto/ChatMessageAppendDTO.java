package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

/**
 * 追加消息 DTO（正常对话或重新生成）
 *
 */
@Getter
@Setter
public class ChatMessageAppendDTO implements SerializableEnable {
    /**
     * 消息角色：user / assistant / system / tool
     */
    private String role;
    /**
     * 消息内容
     */
    private String content;
}
