ALTER TABLE gmd_faq_workflow_run
    MODIFY COLUMN `outputs` mediumtext  COMMENT '运行输出 JSON';
ALTER TABLE gmd_faq_secret_key
    MODIFY COLUMN `value` text NULL COMMENT '密钥' AFTER `name`,
DROP INDEX `idx_value`;
