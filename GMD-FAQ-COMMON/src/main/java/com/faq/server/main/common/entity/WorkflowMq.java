package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@TableName(TableConst.WORKFLOW_MQ)
@NoArgsConstructor
public class WorkflowMq extends BaseTenantEntity {
    @QueryDefine(condition = QueryCondition.EQ)
    private String workflowId;

    @QueryDefine(condition = QueryCondition.EQ)
    private String mqId;

    public WorkflowMq(String workflowId, String mqId) {
        this.workflowId = workflowId;
        this.mqId = mqId;
    }
}
