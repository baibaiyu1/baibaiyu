-- 部门授权按账号当前所属部门实时生效，并覆盖所选部门的全部下级部门。
SET NAMES utf8mb4;

SET @has_table := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_resource_department_grant');
SET @sql := IF(@has_table = 0,
  'CREATE TABLE `gmd_faq_resource_department_grant` (
    `id` bigint NOT NULL,
    `tenant_id` bigint NOT NULL,
    `resource_type` varchar(20) NOT NULL,
    `resource_id` bigint NOT NULL,
    `department_id` bigint NOT NULL,
    `allow_use` tinyint(1) NOT NULL DEFAULT 0,
    `enabled` tinyint(1) NOT NULL DEFAULT 1,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` bigint DEFAULT NULL,
    `updated_by` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_resource_department_grant` (`tenant_id`, `resource_type`, `resource_id`, `department_id`),
    KEY `idx_resource_department_grant` (`tenant_id`, `department_id`, `resource_type`)
  ) COMMENT=''资源部门访问授权''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
