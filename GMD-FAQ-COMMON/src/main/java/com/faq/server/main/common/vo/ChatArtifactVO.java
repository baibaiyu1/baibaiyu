package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话产物 VO
 *
 * <p>刻意不下发 tenantId —— 前端按 sessionId + createTime 把产物挂到对应消息上，
 * 租户归属属于服务端隔离信息，没有下发必要。
 *
 */
@Getter
@Setter
public class ChatArtifactVO implements SerializableEnable {
    private Long id;
    private Long sessionId;

    /** 工作空间内相对路径，前端据此调工作空间预览 */
    private String filePath;
    private String fileName;
    private String extension;
    private Long sizeBytes;

    /** 产出该文件的工具名 */
    private String sourceTool;

    /**
     * 最近一次写入时间。
     * 前端用它把产物挂到「此前最近的一条消息」上 —— 落库走异步队列，
     * 写入时拿不到稳定 messageId，故在读取侧按时间序归属。
     */
    private LocalDateTime createTime;
}
