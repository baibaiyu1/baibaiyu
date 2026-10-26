package com.gmd.faq.server.main.workflowbiz.vo;

import com.gmd.faq.server.main.common.entity.Workflow;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkflowDetailVO {
    private Workflow workflow;
    private WorkflowResourceRefs resources;
}
