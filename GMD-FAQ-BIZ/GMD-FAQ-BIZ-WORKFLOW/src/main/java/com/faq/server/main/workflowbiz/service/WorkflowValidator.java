package com.gmd.faq.server.main.workflowbiz.service;

import com.gmd.faq.server.main.workflowbiz.vo.WorkflowValidationResult;

public interface WorkflowValidator {
    WorkflowValidationResult validate(Object definition);
}
