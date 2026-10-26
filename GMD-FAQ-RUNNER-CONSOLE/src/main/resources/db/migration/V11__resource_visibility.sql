-- ============================================================
-- 资源可见性与收藏：资源表加可见性/归属/血缘列 + 收藏表
-- ============================================================
-- 背景：
--   平台原有资源（智能体 / 工具 / 技能包 / 知识库 / MCP）在租户内对所有人可见，
--   缺少「个人 / 组织公开」的可见性分级、归属人与 Fork 血缘。本次新增：
--     1) 5 张资源表各加 4 列：
--          visibility   可见性：PRIVATE=仅创建者可见，TENANT=本组织可见（默认，等价改造前行为）
--          owner_id     归属人账号ID
--          fork_from_id Fork 来源资源ID
--          fork_root_id Fork 根资源ID（用于追溯同一资源的所有副本）
--     2) 新表 gmd_faq_resource_favorite：账号级收藏（与资源本身无关，不改变资源可见性）
--
--   列名说明：刻意不使用 scope，因为 gmd_faq_model_provider.scope_type 已占用该语义
--   （GLOBAL/TENANT 表示模型供应商作用域），避免同名不同义造成误读。
--
-- 幂等性（重要）：
--   本脚本同时面向「存量库」与「新库（由 sql/db_init.sql 初始化）」两类环境：
--     1) 加列前先查 information_schema，已存在则跳过（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）；
--     2) 建表使用 CREATE TABLE IF NOT EXISTS；
--     3) 存量数据的 visibility 取默认值 TENANT，保证升级后老资源依旧对全租户可见，不会「丢数据」。
--
-- 依赖：不读取其它表数据做回填。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 1. gmd_faq_agent_definition ----------
SET @tbl := 'gmd_faq_agent_definition';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''TENANT'' COMMENT ''可见性：PRIVATE=仅创建者，TENANT=本组织可见'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `owner_id` bigint DEFAULT NULL COMMENT ''归属人账号ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_from_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_from_id` bigint DEFAULT NULL COMMENT ''Fork 来源资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_root_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_root_id` bigint DEFAULT NULL COMMENT ''Fork 根资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_agent_visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_agent_visibility` (`tenant_id`, `visibility`), ADD KEY `idx_agent_owner` (`tenant_id`, `owner_id`)'), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 2. gmd_faq_tool_config ----------
SET @tbl := 'gmd_faq_tool_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''TENANT'' COMMENT ''可见性：PRIVATE=仅创建者，TENANT=本组织可见'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `owner_id` bigint DEFAULT NULL COMMENT ''归属人账号ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_from_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_from_id` bigint DEFAULT NULL COMMENT ''Fork 来源资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_root_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_root_id` bigint DEFAULT NULL COMMENT ''Fork 根资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_tool_visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_tool_visibility` (`tenant_id`, `visibility`), ADD KEY `idx_tool_owner` (`tenant_id`, `owner_id`)'), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 3. gmd_faq_skill_package ----------
SET @tbl := 'gmd_faq_skill_package';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''TENANT'' COMMENT ''可见性：PRIVATE=仅创建者，TENANT=本组织可见'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `owner_id` bigint DEFAULT NULL COMMENT ''归属人账号ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_from_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_from_id` bigint DEFAULT NULL COMMENT ''Fork 来源资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_root_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_root_id` bigint DEFAULT NULL COMMENT ''Fork 根资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_skill_visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_skill_visibility` (`tenant_id`, `visibility`), ADD KEY `idx_skill_owner` (`tenant_id`, `owner_id`)'), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 4. gmd_faq_knowledge_base_config ----------
SET @tbl := 'gmd_faq_knowledge_base_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''TENANT'' COMMENT ''可见性：PRIVATE=仅创建者，TENANT=本组织可见'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `owner_id` bigint DEFAULT NULL COMMENT ''归属人账号ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_from_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_from_id` bigint DEFAULT NULL COMMENT ''Fork 来源资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_root_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_root_id` bigint DEFAULT NULL COMMENT ''Fork 根资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_knowledge_visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_knowledge_visibility` (`tenant_id`, `visibility`), ADD KEY `idx_knowledge_owner` (`tenant_id`, `owner_id`)'), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 5. gmd_faq_mcp_server ----------
SET @tbl := 'gmd_faq_mcp_server';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''TENANT'' COMMENT ''可见性：PRIVATE=仅创建者，TENANT=本组织可见'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `owner_id` bigint DEFAULT NULL COMMENT ''归属人账号ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_from_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_from_id` bigint DEFAULT NULL COMMENT ''Fork 来源资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'fork_root_id') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD COLUMN `fork_root_id` bigint DEFAULT NULL COMMENT ''Fork 根资源ID'''), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_mcp_visibility') = 0,
  CONCAT('ALTER TABLE `', @tbl, '` ADD KEY `idx_mcp_visibility` (`tenant_id`, `visibility`), ADD KEY `idx_mcp_owner` (`tenant_id`, `owner_id`)'), 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 6. 资源收藏表 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_resource_favorite` (
  `id` bigint NOT NULL COMMENT '主键',
  `resource_type` varchar(40) NOT NULL COMMENT '资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP',
  `resource_id` varchar(64) NOT NULL COMMENT '资源ID',
  `account_id` bigint NOT NULL COMMENT '收藏人账号ID',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否有效',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_favorite` (`tenant_id`, `account_id`, `resource_type`, `resource_id`),
  KEY `idx_resource_favorite_account` (`tenant_id`, `account_id`, `resource_type`)
) COMMENT='资源收藏';

SET FOREIGN_KEY_CHECKS = 1;
