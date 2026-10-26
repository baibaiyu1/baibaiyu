package com.gmd.faq.server.main.workflowbiz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.WorkflowNodeExecution;
import com.gmd.faq.server.main.common.entity.WorkflowRun;
import com.gmd.faq.server.main.workflowbiz.dto.WorkflowNodeRunRequest;
import com.gmd.faq.server.main.workflowbiz.dto.WorkflowRunRequest;
import com.gmd.faq.server.main.workflowbiz.vo.WorkflowNodeRunResult;
import com.gmd.faq.server.main.workflowbiz.vo.WorkflowRunResult;

import java.util.List;

public interface WorkflowRunService extends IService<WorkflowRun> {
    WorkflowRunResult debugRun(Long workflowId, WorkflowRunRequest request);

    WorkflowRunResult run(Long workflowId, WorkflowRunRequest request, UserDetail userDetail);

    List<WorkflowNodeExecution> nodeExecutions(Long runId);

    /**
     * 单节点独立运行（不持久化任何日志）。
     */
    WorkflowNodeRunResult debugNodeRun(WorkflowNodeRunRequest request, UserDetail userDetail);
}
