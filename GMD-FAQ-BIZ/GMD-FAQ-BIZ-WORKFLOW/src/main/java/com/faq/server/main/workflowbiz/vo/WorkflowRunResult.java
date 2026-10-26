package com.gmd.faq.server.main.workflowbiz.vo;

import com.gmd.faq.server.main.common.entity.WorkflowNodeExecution;
import com.gmd.faq.server.main.common.entity.WorkflowRun;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class WorkflowRunResult {
    private WorkflowRun run;
    private Object output;
    private List<WorkflowNodeExecution> nodeExecutions;
}
