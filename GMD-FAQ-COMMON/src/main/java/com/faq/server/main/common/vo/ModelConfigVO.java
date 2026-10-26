package com.gmd.faq.server.main.common.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型配置VO
 *
 */
@Data
@EqualsAndHashCode
public class ModelConfigVO implements SerializableEnable {
    private Long id;
    private Long providerId;
    private String name;
    private String modelId;
    private ModelApiType apiType;
    private Boolean responsesSampling;
    private JsonNode modelType;
    private String description;
    private Boolean streaming;
    private Boolean thinking;
    private Integer contextWindow;
    private Integer maxTokens;
    private Double temperature;
    private Double topP;
    private Integer topK;
    private Double repeatPenalty;
    private Long seed;
    private JsonNode extendConfig;
    private com.gmd.faq.server.main.common.model.ReasoningSettings reasoningConfig;
    private com.gmd.faq.server.main.common.model.ReasoningCapabilities reasoningCapabilities;
    private String connectivityStatus;
    private String connectivityMessage;
    private LocalDateTime lastConnectivityCheck;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
}
