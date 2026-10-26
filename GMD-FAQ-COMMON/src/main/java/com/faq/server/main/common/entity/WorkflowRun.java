package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.WorkflowRunStatus;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：工作流执行记录表
 *
 **/
@Getter
@Setter
@TableName(value = TableConst.WORKFLOW_RUN, autoResultMap = true)
public class WorkflowRun extends BaseTenantEntity {
    @QueryDefine(condition = QueryCondition.EQ)
    private String routeId;

    /**
     * 工作流ID
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private String workflowId;
    @QueryDefine(condition = QueryCondition.EQ)
    private String version;
    /**
     * 工作流配置
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private Object config;
    /**
     * 工作流类型
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private WorkflowRunStatus status;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private Object inputs;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private Object outputs;
    private String error;
    private Long startTime;
    private Long endTime;
}
