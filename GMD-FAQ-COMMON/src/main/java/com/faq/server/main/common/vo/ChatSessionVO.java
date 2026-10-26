package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天会话 VO
 *
 */
@Getter
@Setter
public class ChatSessionVO implements SerializableEnable {
    private Long id;
    private Long userId;
    private Long ownerDeptId;
    private Long agentId;
    private Long currentMessageId;
    /**
     * 消息所在归档表名
     */
    private String messageTable;
    private String title;
    private Boolean isPinned;
    private LocalDateTime pinTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 是否为游客会话（归属人不是平台账号，来源是分享链接）
     * <p>
     * 由服务层按「userId 是否存在于 account 表」判定后回填，不是 chat_session 的表字段。
     */
    private Boolean isGuest;

    private Boolean canRead;

    /** 当前调用者是否可以修改会话和工作空间。 */
    private Boolean canWrite;
}
