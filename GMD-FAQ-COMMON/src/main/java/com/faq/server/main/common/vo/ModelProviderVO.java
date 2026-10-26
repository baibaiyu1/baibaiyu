package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.enums.AuthType;
import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.enums.ModelProviderType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 模型提供商VO
 *
 */
@Data
@EqualsAndHashCode
public class ModelProviderVO implements SerializableEnable {
    private Long id;
    private ModelProviderType type;
    private String name;
    private String description;
    private String baseUrl;
    private AuthType authType;
    private String apiKey;
    private String envVarName;
    private Boolean enabled;
    private JsonNode configMeta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
