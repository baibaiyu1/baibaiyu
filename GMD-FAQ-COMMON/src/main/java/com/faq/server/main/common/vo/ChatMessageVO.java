package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天消息 VO
 *
 */
@Getter
@Setter
public class ChatMessageVO implements SerializableEnable {
    private Integer id;
    private Long sessionId;
    private String role;
    private String content;
    private Integer parentId;
    private String path;
    private Integer depth;
    private LocalDateTime createdAt;

    /** Present only for the timeline anchor whose role is {@code subagent}. */
    private SubAgentRunVO subAgentRun;
}
