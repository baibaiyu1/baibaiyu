ALTER TABLE gmd_faq_workflow
    ADD COLUMN `flow_type` VARCHAR(16) NOT NULL DEFAULT 'WORKFLOW' COMMENT '流程类型: WORKFLOW | CHATFLOW';

CREATE INDEX `idx_workflow_flow_type` ON gmd_faq_workflow(`flow_type`);
