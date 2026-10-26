package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统提示词模板VO
 *
 */
@Data
@EqualsAndHashCode
public class SystemPromptTemplateVO implements SerializableEnable {
    private Long id;
    private String category;
    private String name;
    private String description;
    private String content;
    private Integer usageCount;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
}
