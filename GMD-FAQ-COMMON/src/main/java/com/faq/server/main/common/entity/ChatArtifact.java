package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话产物：智能体在一次会话中实际产生/修改的工作空间文件。
 *
 * 与 {@link ChatMessage} 中 role=tool 的记录不同，产物是**确定性**判定的结果 ——
 * 由 ArtifactHook 在工作空间文件发生变更时写入，不依赖模型是否主动引用文件。
 * 前端据此在对话里渲染产物卡片，点击即在右栏打开预览。
 *
 */
@Getter
@Setter
@TableName(TableConst.CHAT_ARTIFACT)
public class ChatArtifact implements SerializableEnable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /**
     * 会话ID，关联 chat_session 表
     */
    private Long sessionId;

    /**
     * 工作空间内的相对路径，会话内唯一
     */
    private String filePath;

    /**
     * 文件名（不含目录）
     */
    private String fileName;

    /**
     * 扩展名（不含点号，小写）
     */
    private String extension;

    /**
     * 文件大小（字节）
     */
    private Long sizeBytes;

    /**
     * 产出该文件的工具名，用于排查来源
     */
    private String sourceTool;

    /**
     * 最近一次写入时间。与 chat_message.created_at 同源（同一 JVM 时钟），
     * 前端按此时间把产物挂到「此前最近的一条消息」上。
     */
    private LocalDateTime createTime;
}
