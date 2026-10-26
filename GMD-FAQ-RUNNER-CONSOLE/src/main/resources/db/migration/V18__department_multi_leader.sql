-- ============================================================
-- 组织负责人支持多选：部门-负责人关联表
-- ============================================================
-- 背景：
--   V17 把「组织」并入了部门树，负责人用单列 gmd_faq_department.leader_account_id 表达，
--   但实际使用中一个组织往往有多位负责人（正/副职、多人共管），单列不够用。
--   本脚本改为「部门 → 多位负责人账号」的多对多关系。
--
-- 内容：
--   1) 新建 gmd_faq_department_leader（department_id + account_id，唯一键防重）；
--   2) 把存量 leader_account_id 播种进新表（幂等：先删同键再加）；
--   3) 删除 gmd_faq_department.leader_account_id（先查 information_schema 再动态执行）。
--
-- 说明：gmd_faq_department.leader（varchar 文本）是更早的历史字段，保留不动。
--
-- 幂等性：建表用 CREATE TABLE IF NOT EXISTS；播种用 DELETE + INSERT ... SELECT；
--        删列先查 information_schema（MySQL 8 无 DROP COLUMN IF EXISTS）。
--
-- 依赖：V17（gmd_faq_department 的组织资料列与 leader_account_id）。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 1. 部门-负责人关联表 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_department_leader` (
  `id` bigint NOT NULL COMMENT '主键',
  `department_id` bigint NOT NULL COMMENT '部门（组织）ID',
  `account_id` bigint NOT NULL COMMENT '负责人账号ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_leader` (`department_id`, `account_id`),
  KEY `idx_dept_leader_account` (`account_id`),
  KEY `idx_dept_leader_tenant` (`tenant_id`)
) COMMENT='部门（组织）负责人（多选）';

-- ---------- 2. 存量单列负责人播种到新表 ----------
SET @has_leader_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'gmd_faq_department'
    AND COLUMN_NAME = 'leader_account_id'
);
SET @seed_sql = IF(@has_leader_col > 0,
  'INSERT IGNORE INTO `gmd_faq_department_leader` (`id`, `department_id`, `account_id`, `tenant_id`)
   SELECT (d.`id` & 0x0FFFFFFFFFFFFFFF) + 6000000000000000000, d.`id`, d.`leader_account_id`, d.`tenant_id`
   FROM `gmd_faq_department` d
   WHERE d.`leader_account_id` IS NOT NULL',
  'SELECT 1');
PREPARE seed_stmt FROM @seed_sql;
EXECUTE seed_stmt;
DEALLOCATE PREPARE seed_stmt;

-- ---------- 3. 删除旧的单列负责人 ----------
SET @drop_leader_sql = IF(@has_leader_col > 0,
  'ALTER TABLE `gmd_faq_department` DROP COLUMN `leader_account_id`',
  'SELECT 1');
PREPARE drop_leader_stmt FROM @drop_leader_sql;
EXECUTE drop_leader_stmt;
DEALLOCATE PREPARE drop_leader_stmt;

SET FOREIGN_KEY_CHECKS = 1;
