package com.gmd.faq.server.main.workflowbiz.dto;

import com.gmd.faq.server.main.node.base.request.ParamItem;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class WorkflowRunRequest {
    private List<ParamItem> params;
    private Map<String, Object> variables;
}
