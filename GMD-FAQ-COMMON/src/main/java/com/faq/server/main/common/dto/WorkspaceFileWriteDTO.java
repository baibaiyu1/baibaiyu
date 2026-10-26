package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作空间文件写入 DTO（右栏「文章编写模式」保存用）
 *
 */
@Getter
@Setter
public class WorkspaceFileWriteDTO implements SerializableEnable {
    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 工作空间内相对路径
     */
    private String filePath;

    /**
     * 新的文件内容
     */
    private String content;

    /**
     * 打开编辑器时的原始内容，用于并发冲突检测。
     *
     * <p>为 null 时跳过检测（新建文件场景）。
     * 用内容比对而非 mtime：内容才是用户真正关心的「我改的是不是我看到的那份」，
     * 且不必把文件时间戳一路透传到前端。
     */
    private String expectedContent;
}
