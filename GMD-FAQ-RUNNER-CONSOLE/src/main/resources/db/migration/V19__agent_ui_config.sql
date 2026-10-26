-- 智能体「功能增强 · Web App 用户体验」配置
-- 存储对话端展示配置：开场白（openingMessage）与推荐问题（openingQuestions，最多 6 条）。
-- 结构示例：{"openingMessage":"你好，我是你的智能助手","openingQuestions":["帮我总结今天的重点"]}
-- 幂等：仅当列不存在时新增（存量库与新库都安全）。
SET @col_exists := (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gmd_faq_agent_definition'
      AND COLUMN_NAME = 'ui_config'
);
SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE gmd_faq_agent_definition ADD COLUMN `ui_config` text DEFAULT NULL COMMENT ''对话端展示配置（开场白/推荐问题）'' AFTER `tag`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
