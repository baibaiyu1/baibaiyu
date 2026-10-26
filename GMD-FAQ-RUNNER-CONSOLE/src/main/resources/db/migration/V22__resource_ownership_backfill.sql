-- ============================================================
-- V22：资源归属回填与默认可见性收紧（5 个资源域）
-- ============================================================
-- 背景（为什么需要这个脚本）：
--   V11 给 5 张资源表（智能体 / 工具 / 技能 / 知识库 / MCP）加了 `visibility` + `owner_id`，
--   但**只有 AgentDefinition 实体映射了这两列**，其余 4 个实体没有该字段，导致：
--     1) 新建资源时 INSERT 语句里根本没有 owner_id → 值恒为 NULL，
--        列表「我创建的」档按 `owner_id = 当前账号` 过滤，于是新建的资源永远不出现在「我创建的」；
--     2) visibility 只能吃列默认值 `TENANT`，于是**每条新资源都自动变成组织共享**，
--        再叠加「数据范围放开无归属人资源」的旧口径，兄弟部门之间互相可见。
--
--   代码侧（本次同期改动）：
--     · ownerId / visibility 上提到 BaseTenantEntity + OwnedResource 契约；
--     · ResourceOwnershipSupport.initOwnership 在创建时写入 owner_id = 当前账号、
--       visibility 默认 PRIVATE（不再自动进组织空间）；
--     · ResourceOwnershipSupport.assertManageable 给改 / 删 / 激活 / 同步等单条操作补上归属校验；
--     · 数据范围口径改为「本部门子树 + 全部上级部门」，组织公共资源池（owner_id IS NULL）全员可见。
--   本脚本负责把**存量数据**对齐到同一口径。
--
-- 幂等性（对存量库与新库都安全）：
--   · 不直接引用可能不存在的表：先查 information_schema 再用 PREPARE/EXECUTE 动态执行
--     （与 V7/V11 同一套写法，不引入 DELIMITER / 存储过程）；
--   · 只做 UPDATE，且都带 WHERE 守卫（owner_id IS NULL / visibility IS NULL），重复执行无副作用；
--   · 不做 INSERT/DELETE，不换主键，不产生悬挂引用。
--
-- 依赖：V11（visibility / owner_id 列）、V9（账号角色表）。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 存量回填：owner_id 为空但有 created_by 的资源 → 归属人为创建者
--    回填之后「我创建的」能查到自己的历史资源，且这些资源受归属人的部门边界约束
--    （本部门子树 + 上级部门），不再是一旦 visibility='TENANT' 就全租户可见。
-- ------------------------------------------------------------
SET @tbl := 'gmd_faq_agent_definition';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `owner_id` = `created_by` WHERE `owner_id` IS NULL AND `created_by` IS NOT NULL'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_tool_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `owner_id` = `created_by` WHERE `owner_id` IS NULL AND `created_by` IS NOT NULL AND `tool_type` <> ', QUOTE('BUILTIN')),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_skill_package';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `owner_id` = `created_by` WHERE `owner_id` IS NULL AND `created_by` IS NOT NULL'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_knowledge_base_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `owner_id` = `created_by` WHERE `owner_id` IS NULL AND `created_by` IS NOT NULL'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_mcp_server';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `owner_id` = `created_by` WHERE `owner_id` IS NULL AND `created_by` IS NOT NULL'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. 内置工具显式保持「组织公共资源池」：owner_id 置空 + 组织共享
--    内置工具由代码同步（ToolServiceImpl.SyncConfigToDatabase），属于平台资产，
--    不能因为 created_by 恰好是某个管理员就变成「该管理员的个人资源」而只对其部门可见。
-- ------------------------------------------------------------
SET @tbl := 'gmd_faq_tool_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner_id') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `owner_id` = NULL, `visibility` = ''TENANT'' WHERE `tool_type` = ', QUOTE('BUILTIN')),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. 存量可见性口径修正：把「从未被主动共享」的自动 TENANT 收紧回 PRIVATE
--    判断依据：归属人只是普通业务账号（其角色数据范围都不是 ALL），
--    说明资源不是平台/管理员发布的标准件；这类 TENANT 来自列默认值，而非用户的选择。
--    保留 TENANT 的两类：
--      a) 所有权属于管理员/ALL 数据范围账号的资源（平台公共件，如内置工具池）；
--      b) owner_id 为空的组织公共资源池。
--    影响：这类资源的可见范围从「全租户」收窄为「归属人 + 管理员」，
--    与「新建资源默认仅归属人可见」的口径一致；确需共享时由归属人在卡片菜单重新打开。
-- ------------------------------------------------------------
SET @tbl := 'gmd_faq_agent_definition';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE''',
         ' WHERE `visibility` = ''TENANT'' AND `owner_id` IS NOT NULL',
         ' AND `owner_id` NOT IN (SELECT `account_id` FROM (',
         '   SELECT ra.`account_id` FROM `gmd_faq_role_account` ra',
         '   JOIN `gmd_faq_role` r ON r.`id` = ra.`role_id`',
         '   WHERE r.`data_scope` = ''ALL''',
         ' ) AS v22_platform_owner)'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_tool_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE''',
         ' WHERE `visibility` = ''TENANT'' AND `owner_id` IS NOT NULL',
         ' AND `owner_id` NOT IN (SELECT `account_id` FROM (',
         '   SELECT ra.`account_id` FROM `gmd_faq_role_account` ra',
         '   JOIN `gmd_faq_role` r ON r.`id` = ra.`role_id`',
         '   WHERE r.`data_scope` = ''ALL''',
         ' ) AS v22_platform_owner)'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_skill_package';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE''',
         ' WHERE `visibility` = ''TENANT'' AND `owner_id` IS NOT NULL',
         ' AND `owner_id` NOT IN (SELECT `account_id` FROM (',
         '   SELECT ra.`account_id` FROM `gmd_faq_role_account` ra',
         '   JOIN `gmd_faq_role` r ON r.`id` = ra.`role_id`',
         '   WHERE r.`data_scope` = ''ALL''',
         ' ) AS v22_platform_owner)'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_knowledge_base_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE''',
         ' WHERE `visibility` = ''TENANT'' AND `owner_id` IS NOT NULL',
         ' AND `owner_id` NOT IN (SELECT `account_id` FROM (',
         '   SELECT ra.`account_id` FROM `gmd_faq_role_account` ra',
         '   JOIN `gmd_faq_role` r ON r.`id` = ra.`role_id`',
         '   WHERE r.`data_scope` = ''ALL''',
         ' ) AS v22_platform_owner)'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_mcp_server';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE''',
         ' WHERE `visibility` = ''TENANT'' AND `owner_id` IS NOT NULL',
         ' AND `owner_id` NOT IN (SELECT `account_id` FROM (',
         '   SELECT ra.`account_id` FROM `gmd_faq_role_account` ra',
         '   JOIN `gmd_faq_role` r ON r.`id` = ra.`role_id`',
         '   WHERE r.`data_scope` = ''ALL''',
         ' ) AS v22_platform_owner)'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. 可见性兜底：visibility 为空/空串的历史行按 PRIVATE 处理
-- ------------------------------------------------------------
SET @tbl := 'gmd_faq_agent_definition';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE'' WHERE `visibility` IS NULL OR `visibility` = ', QUOTE('')), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_tool_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE'' WHERE `visibility` IS NULL OR `visibility` = ', QUOTE('')), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_skill_package';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE'' WHERE `visibility` IS NULL OR `visibility` = ', QUOTE('')), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_knowledge_base_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE'' WHERE `visibility` IS NULL OR `visibility` = ', QUOTE('')), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_mcp_server';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('UPDATE `', @tbl, '` SET `visibility` = ''PRIVATE'' WHERE `visibility` IS NULL OR `visibility` = ', QUOTE('')), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 5. 新建资源的默认可见性收紧为 PRIVATE（列默认值仍是 V11 的 TENANT）
--    实体侧已显式写 visibility，列默认值只对「绕过服务层的裸 INSERT」生效；
--    这里把默认值也对齐，避免运维脚本或手工 SQL 建出的资源又变成组织共享。
-- ------------------------------------------------------------
SET @tbl := 'gmd_faq_agent_definition';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('ALTER TABLE `', @tbl, '` MODIFY COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''可见性：PRIVATE=仅归属人，TENANT=部门共享（本部门及上级部门）'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_tool_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('ALTER TABLE `', @tbl, '` MODIFY COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''可见性：PRIVATE=仅归属人，TENANT=部门共享（本部门及上级部门）'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_skill_package';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('ALTER TABLE `', @tbl, '` MODIFY COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''可见性：PRIVATE=仅归属人，TENANT=部门共享（本部门及上级部门）'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_knowledge_base_config';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('ALTER TABLE `', @tbl, '` MODIFY COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''可见性：PRIVATE=仅归属人，TENANT=部门共享（本部门及上级部门）'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl := 'gmd_faq_mcp_server';
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'visibility') > 0,
  CONCAT('ALTER TABLE `', @tbl, '` MODIFY COLUMN `visibility` varchar(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''可见性：PRIVATE=仅归属人，TENANT=部门共享（本部门及上级部门）'''), 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
