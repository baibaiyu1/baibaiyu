package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;

/**
 * 描述：工作空间容量信息 VO
 *
 **/
@Data
public class WorkspaceCapacityVO implements SerializableEnable {
    /**
     * 已使用空间（字节）
     */
    private long usedBytes;
    /**
     * 最大容量（字节）
     */
    private long maxBytes;
    /**
     * 已使用空间可读格式（如 "5.2 MB"）
     */
    private String usedReadable;
    /**
     * 最大容量可读格式（如 "30.0 MB"）
     */
    private String maxReadable;
    /**
     * 使用百分比（0~100）
     */
    private double percent;
}
