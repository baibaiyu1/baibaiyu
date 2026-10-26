-- 固定五类资源创建时的部门，并提供逐账号的查看/使用授权。
-- 历史资源按迁移时归属人的部门回填；无归属或无部门的资源保持 NULL。
SET NAMES utf8mb4;

SET @tbl := 'gmd_faq_agent_definition';
SET @add_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'resource_dept_id') = 0;
SET @sql := IF(@add_col,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `resource_dept_id` bigint DEFAULT NULL COMMENT ''创建时所属部门'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF(@add_col,
  CONCAT('UPDATE `', @tbl, '` r JOIN `gmd_faq_account` a ON a.id = r.owner_id JOIN `gmd_faq_department` d ON d.id = a.dept_id AND d.tenant_id = r.tenant_id SET r.resource_dept_id = d.id WHERE r.resource_dept_id IS NULL'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_agent_resource_dept') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_agent_resource_dept` (`tenant_id`, `resource_dept_id`)'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_tool_config';
SET @add_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'resource_dept_id') = 0;
SET @sql := IF(@add_col,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `resource_dept_id` bigint DEFAULT NULL COMMENT ''创建时所属部门'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF(@add_col,
  CONCAT('UPDATE `', @tbl, '` r JOIN `gmd_faq_account` a ON a.id = r.owner_id JOIN `gmd_faq_department` d ON d.id = a.dept_id AND d.tenant_id = r.tenant_id SET r.resource_dept_id = d.id WHERE r.resource_dept_id IS NULL'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_tool_resource_dept') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_tool_resource_dept` (`tenant_id`, `resource_dept_id`)'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_skill_package';
SET @add_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'resource_dept_id') = 0;
SET @sql := IF(@add_col,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `resource_dept_id` bigint DEFAULT NULL COMMENT ''创建时所属部门'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF(@add_col,
  CONCAT('UPDATE `', @tbl, '` r JOIN `gmd_faq_account` a ON a.id = r.owner_id JOIN `gmd_faq_department` d ON d.id = a.dept_id AND d.tenant_id = r.tenant_id SET r.resource_dept_id = d.id WHERE r.resource_dept_id IS NULL'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_skill_resource_dept') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_skill_resource_dept` (`tenant_id`, `resource_dept_id`)'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_knowledge_base_config';
SET @add_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'resource_dept_id') = 0;
SET @sql := IF(@add_col,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `resource_dept_id` bigint DEFAULT NULL COMMENT ''创建时所属部门'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF(@add_col,
  CONCAT('UPDATE `', @tbl, '` r JOIN `gmd_faq_account` a ON a.id = r.owner_id JOIN `gmd_faq_department` d ON d.id = a.dept_id AND d.tenant_id = r.tenant_id SET r.resource_dept_id = d.id WHERE r.resource_dept_id IS NULL'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_knowledge_resource_dept') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_knowledge_resource_dept` (`tenant_id`, `resource_dept_id`)'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_mcp_server';
SET @add_col := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'resource_dept_id') = 0;
SET @sql := IF(@add_col,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `resource_dept_id` bigint DEFAULT NULL COMMENT ''创建时所属部门'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF(@add_col,
  CONCAT('UPDATE `', @tbl, '` r JOIN `gmd_faq_account` a ON a.id = r.owner_id JOIN `gmd_faq_department` d ON d.id = a.dept_id AND d.tenant_id = r.tenant_id SET r.resource_dept_id = d.id WHERE r.resource_dept_id IS NULL'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_mcp_resource_dept') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_mcp_resource_dept` (`tenant_id`, `resource_dept_id`)'), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `gmd_faq_resource_access_grant` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `resource_type` varchar(20) NOT NULL,
  `resource_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `allow_use` tinyint(1) NOT NULL DEFAULT 0,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_access_grant` (`tenant_id`, `resource_type`, `resource_id`, `account_id`),
  KEY `idx_resource_grant_account` (`tenant_id`, `account_id`, `resource_type`)
) COMMENT='资源逐账号访问授权';
