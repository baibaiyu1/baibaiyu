package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.enums.SensitiveWordAction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 敏感词配置VO
 *
 */
@Data
@EqualsAndHashCode
public class SensitiveWordConfigVO implements SerializableEnable {
    private Long id;
    private String category;
    private String name;
    private String description;
    private JsonNode words;
    private SensitiveWordAction action;
    private String replacement;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
}
