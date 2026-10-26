package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.enums.HookType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Hook配置VO
 *
 */
@Data
@EqualsAndHashCode
public class HookConfigVO implements SerializableEnable {
    private Long id;
    private String name;
    private HookType hookType;
    private String description;
    private String classPath;
    private String code;
    private Integer priority;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
}
