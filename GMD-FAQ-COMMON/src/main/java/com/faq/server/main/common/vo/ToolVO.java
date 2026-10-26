package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.enums.CodeLanguage;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具VO
 *
 */
@Data
@EqualsAndHashCode
public class ToolVO implements SerializableEnable {
    private Long id;
    private String name;
    private String toolId;
    private String description;
    private CodeLanguage language;
    private String category;
    private ToolType toolType;
    private JsonNode inputSchema;
    private JsonNode outputSchema;
    private Boolean needConfirm;
    private Boolean returnDirect;
    private String classPath;
    private String code;
    private String version;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
}
