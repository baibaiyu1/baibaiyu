package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.HealthStatus;
import com.gmd.faq.server.main.common.enums.McpActivationStatus;
import com.gmd.faq.server.main.common.enums.McpFailureSource;
import com.gmd.faq.server.main.common.enums.McpMode;
import com.gmd.faq.server.main.common.enums.McpProtocol;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * MCP 服务器配置
 *
 */
@Getter
@Setter
@TableName(value = TableConst.MCP, autoResultMap = true)
public class McpServer extends BaseTenantEntity implements OwnedResource {

    /**
     * 服务器名称
     */
    private String name;

    /**
     * 协议类型
     */
    private McpProtocol protocol;

    /**
     * 运行模式
     */
    private McpMode mode;

    /**
     * 超时时间
     */
    private Integer timeout;

    /**
     * 协议配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode protocolConfig;

    /**
     * 描述
     */
    private String description;

    /**
     * 缓存的工具目录 JSON
     */
    private String toolSchemas;

    /**
     * 健康状态
     */
    private HealthStatus healthStatus;

    /**
     * 上次健康检查时间
     */
    private LocalDateTime lastHealthCheck;

    /**
     * 激活状态
     */
    private McpActivationStatus activationStatus;

    /**
     * 激活结果说明
     */
    private String activationMessage;

    /**
     * 失败来源
     */
    private McpFailureSource failureSource;

    /**
     * 激活状态最近一次变更时间
     */
    private LocalDateTime activationStatusChangedAt;

    /**
     * 上次激活时间
     */
    private LocalDateTime lastActivationTime;

    /**
     * 上次工具同步时间
     */
    private LocalDateTime lastToolSyncTime;

    /**
     * 当前工具数量
     */
    private Integer toolCount;

    /**
     * 自动降级连续失败阈值
     */
    private Integer runtimeFailThreshold;

    /**
     * 激活版本号
     */
    private Long activationRevision;

    /**
     * 当前配置摘要
     */
    private String configHash;

    /**
     * 是否需要同步
     */
    private Boolean needsSync;

    /**
     * 当前激活请求ID
     */
    private String activationRequestId;

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
