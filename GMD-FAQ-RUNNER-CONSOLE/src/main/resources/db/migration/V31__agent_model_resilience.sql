-- 独立模型容错配置；不与采样参数覆盖共用开关。
SET @model_resilience_table_exists = (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_agent_definition');
SET @model_resilience_column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_agent_definition' AND column_name = 'model_resilience_config');
SET @model_resilience_sql = IF(@model_resilience_table_exists > 0 AND @model_resilience_column_exists = 0,
  'ALTER TABLE gmd_faq_agent_definition ADD COLUMN model_resilience_config JSON NULL COMMENT ''模型容错与摘要模型配置''', 'SELECT 1');
PREPARE model_resilience_stmt FROM @model_resilience_sql;
EXECUTE model_resilience_stmt;
DEALLOCATE PREPARE model_resilience_stmt;
