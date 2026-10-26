package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.CodeLanguage;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

/**
 * 工具表
 *
 */
@Getter
@Setter
@TableName(value = TableConst.TOOL, autoResultMap = true)
public class ToolConfig extends BaseTenantEntity implements OwnedResource {

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具编号
     */
    private String toolId;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具分类
     */
    private String category;

    /**
     * 工具类型: 内置/自定义
     */
    private ToolType toolType;

    /**
     * 是否需要确认
     */
    private Boolean needConfirm;

    /** 成功结果直接作为本轮回答，不再请求模型加工。 */
    private Boolean returnDirect;

    /**
     * 输入参数schema
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode inputSchema;

    /**
     * 输出格式schema
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode outputSchema;

    /**
     * 工具路径（tool_type为SYSTEM时使用）
     */
    private String classPath;

    /**
     * 代码语言（tool_type为CUSTOM时使用）
     */
    private CodeLanguage language;

    /**
     * 代码（tool_type为CUSTOM时使用）
     */
    private String code;

    /**
     * 版本号
     */
    private String version;

    /**
     * 作用域类型: GLOBAL=全局, TENANT=指定租户
     */
    private ScopeType scopeType;

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
