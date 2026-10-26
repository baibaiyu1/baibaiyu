package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.HealthStatus;
import com.gmd.faq.server.main.common.enums.KbType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.rag.RAGMode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库配置
 *
 */
@Getter
@Setter
@TableName(value = TableConst.KNOWLEDGE, autoResultMap = true)
public class KnowledgeBaseConfig extends BaseTenantEntity implements OwnedResource {

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库类型
     */
    private KbType kbType;

    /**
     * 集成模式
     */
    private RAGMode ragMode;

    /**
     * 描述
     */
    private String description;

    /**
     * 连接配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode connectionConfig;

    /**
     * 端点配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode endpointConfig;

    /**
     * 检索配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode retrievalConfig;

    /**
     * 重排序配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode rerankingConfig;

    /**
     * 查询重写配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode queryRewriteConfig;

    /**
     * 元数据过滤
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode metadataFilters;

    /**
     * HTTP配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode httpConfig;

    /**
     * 健康状态
     */
    private HealthStatus healthStatus;

    /**
     * 最后同步时间
     */
    private LocalDateTime lastSyncTime;

    /**
     * 个人资产归属账号；部门和平台资产为空，结合 ownershipType 判断归属。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long ownerId;

    /** 创建时所属部门；归属人调部门后保持不变。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long resourceDeptId;

    /**
     * 可见性：PRIVATE 按资产归属限制访问；TENANT 扩大到所属部门分支或平台。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String visibility;
    /** 资产归属；普通编辑不可变更，移交走资源治理接口。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String ownershipType;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long owningDeptId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long maintainerId;
}
