package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.entity.JobInfo;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.enums.ToolChoiceStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.model.StructuredOutputReminder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体定义VO
 *
 */
@Data
@EqualsAndHashCode
public class AgentDefinitionVO implements SerializableEnable {
    private Long id;
    private AgentType agentType;
    private String name;
    private String agentCode;
    private String description;
    private Long modelConfigId;
    private JsonNode modelParamsOverride;
    private com.gmd.faq.server.main.common.model.ReasoningSettings reasoningConfig;
    private JsonNode modelResilienceConfig;

    /**
     * RAG检索参数配置（topK/scoreThreshold/ragMode）
     */
    private JsonNode ragConfig;

    private List<Long> skill;
    private List<Long> workflow;
    private List<Long> tool;
    private List<Long> mcp;
    private List<AgentMcpBindingVO> mcpBindings;
    private List<Long> hook;
    private List<Long> subAgent;
    private List<Long> knowledgeBase;
    private ToolChoiceStrategy toolChoiceStrategy;
    private String specificToolName;
    private Long systemPromptTemplateId;
    private Boolean followTemplate;
    private String systemPrompt;
    private Long sensitiveWordConfigId;
    private Boolean sensitiveFilterEnabled;
    private Integer maxIterations;
    private Boolean enablePlanning;
    private Integer maxSubtasks;
    private Boolean requirePlanConfirmation;
    private Boolean showToolProcess;
    private Boolean enableMemory;
    private Boolean enableMemoryCompression;
    private JsonNode memoryCompressionConfig;
    private Boolean structuredOutputEnabled;
    private StructuredOutputReminder structuredOutputReminder;
    private JsonNode structuredOutputSchema;
    private String version;
    private String tag;
    private List<String> tags;

    /**
     * 对话端展示配置（开场白 openingMessage / 推荐问题 openingQuestions）
     */
    private JsonNode uiConfig;

    private Boolean enabled;
    private Boolean published;
    private Boolean externalShareEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
    private AgentA2A agentA2A;
    private JobInfo jobInfo;
    private Long studioConfigId;
    private Long codeExecutionConfigId;
    private Long longTermMemoryConfigId;

    /**
     * 可见性：PRIVATE=仅创建者可见，TENANT=本组织可见
     */
    private String visibility;

    /**
     * 归属人账号ID
     */
    private Long ownerId;

    /**
     * Fork 来源资源ID
     */
    private Long forkFromId;

    /**
     * Fork 根资源ID
     */
    private Long forkRootId;
}
