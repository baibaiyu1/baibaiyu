package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.enums.ToolChoiceStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.model.StructuredOutputReminder;
import lombok.Getter;
import lombok.Setter;

/**
 * 智能体定义
 *
 */
@Getter
@Setter
@TableName(value = TableConst.AGENT, autoResultMap = true)
public class AgentDefinition extends BaseTenantEntity implements OwnedResource {
    /**
     * 智能体类型
     */
    private AgentType agentType;

    /**
     * 智能体名称
     */
    private String name;

    /**
     * 智能体代码
     */
    private String agentCode;

    /**
     * 智能体描述
     */
    private String description;

    /**
     * 基础模型配置ID
     */
    private Long modelConfigId;

    /**
     * 模型参数覆盖
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode modelParamsOverride;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode modelResilienceConfig;

    /**
     * rag的参数
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode ragConfig;

    /**
     * 工具选择策略
     */
    private ToolChoiceStrategy toolChoiceStrategy;

    /**
     * 指定工具名称
     */
    private String specificToolName;

    /**
     * 系统提示词模板ID
     */
    private Long systemPromptTemplateId;

    /**
     * 是否跟随模板变化
     */
    private Boolean followTemplate;

    /**
     * 系统提示词内容
     */
    private String systemPrompt;

    /**
     * 敏感词配置ID
     */
    private Long sensitiveWordConfigId;

    /**
     * 是否启用敏感词过滤
     */
    private Boolean sensitiveFilterEnabled;

    /**
     * React最大迭代次数
     */
    private Integer maxIterations;

    /**
     * 是否启用计划
     */
    private Boolean enablePlanning;

    /**
     * 是否显示工具调用过程
     */
    private Boolean showToolProcess;

    /**
     * 最大子任务数
     */
    private Integer maxSubtasks;

    /**
     * 计划是否需要确认
     */
    private Boolean requirePlanConfirmation;

    /**
     * 是否启用记忆
     */
    private Boolean enableMemory;

    /**
     * 是否启用记忆压缩
     */
    private Boolean enableMemoryCompression;

    /**
     * 记忆压缩配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode memoryCompressionConfig;

    /**
     * 是否启用结构化输出
     */
    private Boolean structuredOutputEnabled;

    /**
     * 结构化输出模式
     */
    private StructuredOutputReminder structuredOutputReminder;

    /**
     * 结构化输出模板
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode structuredOutputSchema;

    /**
     * 版本号
     */
    private String version;

    /**
     * 标签
     */
    private String tag;

    /** 多标签；tag 保留首个标签供旧接口兼容。 */
    @TableField(value = "tags_json", typeHandler = JsonNodeTypeHandler.class)
    private JsonNode tagsJson;

    /**
     * 对话端展示配置（开场白 / 推荐问题，见 V19 迁移）
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode uiConfig;

    /**
     * 可见性：PRIVATE 按资产归属限制访问；TENANT 扩大到所属部门分支或平台。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String visibility;

    /**
     * 个人资产归属账号；部门和平台资产为空，结合 ownershipType 判断归属。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long ownerId;

    /** 创建时所属部门；归属人调部门后保持不变。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long resourceDeptId;

    /** Approved for use by other accounts. New agents start as drafts. */
    private Boolean published;

    /** External ChatKey access, enabled only by an administrator. */
    private Boolean externalShareEnabled;

    /**
     * Fork 来源资源ID
     */
    private Long forkFromId;

    /**
     * Fork 根资源ID
     */
    private Long forkRootId;
    /** 资产归属；普通编辑不可变更，移交走资源治理接口。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String ownershipType;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long owningDeptId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long maintainerId;
}
