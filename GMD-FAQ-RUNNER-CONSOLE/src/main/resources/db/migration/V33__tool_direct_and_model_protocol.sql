-- 存量工具不直接返回，存量模型继续使用 Chat Completions。
SET @direct_tool_table = (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_tool_config');
SET @direct_tool_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_tool_config' AND column_name = 'return_direct');
SET @direct_tool_sql = IF(@direct_tool_table > 0 AND @direct_tool_column = 0,
  'ALTER TABLE gmd_faq_tool_config ADD COLUMN return_direct BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''成功结果直接作为回答''', 'SELECT 1');
PREPARE direct_tool_stmt FROM @direct_tool_sql;
EXECUTE direct_tool_stmt;
DEALLOCATE PREPARE direct_tool_stmt;

SET @model_protocol_table = (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_model_config');
SET @model_protocol_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_model_config' AND column_name = 'api_type');
SET @model_protocol_sql = IF(@model_protocol_table > 0 AND @model_protocol_column = 0,
  'ALTER TABLE gmd_faq_model_config ADD COLUMN api_type VARCHAR(32) NOT NULL DEFAULT ''CHAT_COMPLETIONS'' COMMENT ''OpenAI 请求协议''', 'SELECT 1');
PREPARE model_protocol_stmt FROM @model_protocol_sql;
EXECUTE model_protocol_stmt;
DEALLOCATE PREPARE model_protocol_stmt;

SET @model_sampling_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_model_config' AND column_name = 'responses_sampling');
SET @model_sampling_sql = IF(@model_protocol_table > 0 AND @model_sampling_column = 0,
  'ALTER TABLE gmd_faq_model_config ADD COLUMN responses_sampling BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''Responses 发送采样参数''', 'SELECT 1');
PREPARE model_sampling_stmt FROM @model_sampling_sql;
EXECUTE model_sampling_stmt;
DEALLOCATE PREPARE model_sampling_stmt;
