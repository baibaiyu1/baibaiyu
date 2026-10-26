package com.gmd.faq.server.main.runtime.endpoint;

import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.workflowbiz.dto.WorkflowNodeRunRequest;
import com.gmd.faq.server.main.workflowbiz.dto.WorkflowRunRequest;
import com.gmd.faq.server.main.workflowbiz.service.WorkflowRunService;
import com.gmd.faq.server.main.workflowbiz.vo.WorkflowNodeRunResult;
import com.gmd.faq.server.main.workflowbiz.vo.WorkflowRunResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 描述：工作流端点
 *
 **/
@RestController
@RequestMapping("/runtime/workflow")
@RequiredArgsConstructor
public class WorkflowEndPoint {
    private final WorkflowRunService workflowRunService;

    @PostMapping("/{id}/debug-run")
    public R<WorkflowRunResult> debugRun(@PathVariable("id") Long id, @RequestBody(required = false) WorkflowRunRequest request) {
        return R.data(workflowRunService.debugRun(id, request));
    }

    @PostMapping("/{id}/run")
    public R<WorkflowRunResult> run(@PathVariable("id") Long id, @RequestBody(required = false) WorkflowRunRequest request) {
        return R.data(workflowRunService.run(id, request, UserUtils.getUserDetail()));
    }

    /**
     * 单节点独立运行（调试节点，不持久化日志）。
     */
    @PostMapping("/debug-node-run")
    public R<WorkflowNodeRunResult> debugNodeRun(@RequestBody WorkflowNodeRunRequest request) {
        return R.data(workflowRunService.debugNodeRun(request, UserUtils.getUserDetail()));
    }
}
