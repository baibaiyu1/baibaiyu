package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.workflow.WorkflowStatus;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：工作流表
 *
 **/
@Getter
@Setter
@TableName(value = TableConst.WORKFLOW, autoResultMap = true)
public class Workflow extends BaseTenantEntity {
    @QueryDefine(condition = QueryCondition.LIKE)
    private String name;

    private String remark;

    /**
     * 路由ID
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private String routeId;
    /**
     * 工作流状态
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private WorkflowStatus status;
    /**
     * 工作流版本
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private String version;
    /**
     * 工作流配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private Object config;

    /**
     * 工作流锁（1表示锁定，0表示未锁定）
     */
    private Integer locked;
}
