-- MCP 服务和工具描述保留全文，兼容超过 4000 字符的工具目录。
-- 缺表、缺列时跳过；已为 MEDIUMTEXT / LONGTEXT 时不再修改，避免缩小容量。
SET NAMES utf8mb4;

SET @mcp_server_description_sql = IF(EXISTS (
  SELECT 1 FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_mcp_server'
    AND column_name = 'description' AND data_type IN ('char', 'varchar', 'tinytext', 'text')
),
  'ALTER TABLE `gmd_faq_mcp_server` MODIFY COLUMN `description` MEDIUMTEXT DEFAULT NULL COMMENT ''描述''',
  'SELECT 1');
PREPARE mcp_server_description_stmt FROM @mcp_server_description_sql;
EXECUTE mcp_server_description_stmt;
DEALLOCATE PREPARE mcp_server_description_stmt;

SET @mcp_tool_description_sql = IF(EXISTS (
  SELECT 1 FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_mcp_tool'
    AND column_name = 'description' AND data_type IN ('char', 'varchar', 'tinytext', 'text')
),
  'ALTER TABLE `gmd_faq_mcp_tool` MODIFY COLUMN `description` MEDIUMTEXT DEFAULT NULL COMMENT ''工具描述''',
  'SELECT 1');
PREPARE mcp_tool_description_stmt FROM @mcp_tool_description_sql;
EXECUTE mcp_tool_description_stmt;
DEALLOCATE PREPARE mcp_tool_description_stmt;
