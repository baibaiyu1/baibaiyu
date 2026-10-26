-- Preserve every selected tag while keeping the old tag column as the first tag.
SET @has_agent_table := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition');
SET @has_tags_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition'
    AND COLUMN_NAME = 'tags_json');
SET @sql := IF(@has_agent_table = 1 AND @has_tags_column = 0,
  'ALTER TABLE gmd_faq_agent_definition ADD COLUMN tags_json JSON NULL AFTER tag', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_tags_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition'
    AND COLUMN_NAME = 'tags_json');
SET @sql := IF(@has_agent_table = 1 AND @has_tags_column = 1,
  'UPDATE gmd_faq_agent_definition SET tags_json = JSON_ARRAY(tag)
   WHERE tags_json IS NULL AND tag IS NOT NULL AND CHAR_LENGTH(tag) > 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_agent_table = 1 AND @has_tags_column = 1,
  'UPDATE gmd_faq_agent_definition SET tags_json = JSON_ARRAY() WHERE tags_json IS NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
