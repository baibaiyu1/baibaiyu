package com.gmd.faq.server.main.workflowbiz.dto;

import com.gmd.faq.server.main.common.enums.WorkflowRunStatus;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkflowRunQueryDTO extends PageParams {
    @QueryDefine(condition = QueryCondition.EQ)
    private String workflowId;

    @QueryDefine(condition = QueryCondition.EQ)
    private String routeId;

    @QueryDefine(condition = QueryCondition.EQ)
    private WorkflowRunStatus status;
}
