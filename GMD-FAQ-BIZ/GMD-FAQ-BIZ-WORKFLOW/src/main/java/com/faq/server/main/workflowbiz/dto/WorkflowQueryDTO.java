package com.gmd.faq.server.main.workflowbiz.dto;

import com.gmd.faq.server.main.common.enums.workflow.WorkflowStatus;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkflowQueryDTO extends PageParams {
    @QueryDefine(condition = QueryCondition.LIKE)
    private String name;

    @QueryDefine(condition = QueryCondition.EQ)
    private String routeId;

    @QueryDefine(condition = QueryCondition.EQ)
    private WorkflowStatus status;

    @QueryDefine(condition = QueryCondition.EQ)
    private Boolean enabled;
}
