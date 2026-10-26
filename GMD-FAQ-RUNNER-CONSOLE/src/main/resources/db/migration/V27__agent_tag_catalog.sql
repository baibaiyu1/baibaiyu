-- Tenant-wide tag catalog, independent of agent assignments.
CREATE TABLE IF NOT EXISTS `gmd_faq_agent_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tag_tenant_name` (`tenant_id`, `name`)
) COMMENT='智能体标签字典';

SET @has_agent_table := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition');
SET @has_tag_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition' AND COLUMN_NAME = 'tag');
SET @sql := IF(@has_agent_table = 1 AND @has_tag_column = 1,
  'INSERT IGNORE INTO `gmd_faq_agent_tag` (`tenant_id`, `name`)
   SELECT DISTINCT a.tenant_id, TRIM(a.tag) FROM `gmd_faq_agent_definition` a
   WHERE a.tag IS NOT NULL AND CHAR_LENGTH(TRIM(a.tag)) BETWEEN 1 AND 100', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_tags_column := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition' AND COLUMN_NAME = 'tags_json');
SET @sql := IF(@has_agent_table = 1 AND @has_tags_column = 1,
  'INSERT IGNORE INTO `gmd_faq_agent_tag` (`tenant_id`, `name`)
   SELECT DISTINCT a.tenant_id, TRIM(j.name)
   FROM `gmd_faq_agent_definition` a,
     JSON_TABLE(a.tags_json, ''$[*]'' COLUMNS (`name` varchar(100) PATH ''$'')) j
   WHERE j.name IS NOT NULL AND CHAR_LENGTH(TRIM(j.name)) BETWEEN 1 AND 100', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
