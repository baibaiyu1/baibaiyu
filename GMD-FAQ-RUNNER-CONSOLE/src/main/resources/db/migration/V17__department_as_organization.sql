-- ============================================================
-- 组织概念与部门概念合并：部门即组织 + 组织角色并入角色表（部门级「主管」标记）
-- ============================================================
-- 背景（为什么需要这个脚本）：
--   此前平台有两套并行的「组织」模型，系统设置里重复且互相冲突：
--     1) gmd_faq_tenant（组织/租户）+ gmd_faq_account_tenant.role（拥有者/管理员/编辑者/查看者）
--        —— 由「系统设置 - 组织管理 - 成员管理」维护；
--     2) gmd_faq_department（部门树，已带 tenant_id）+ gmd_faq_role（角色，tenant_id 隔离）
--        —— 由「系统设置 - 部门列表 / 角色配置 / 用户列表」维护。
--   而 account_tenant.role 旧枚举自 V13 起已不参与任何鉴权，纯展示，属于历史包袱。
--
--   本次统一为：**部门树的每个节点就是一个组织**，
--     用户 account.dept_id 指向哪个部门，就属于哪个组织（组织成员 = 该部门及其下级的账号）。
--   于是：
--     * 组织资料落在部门表上（编码/描述/联系人/联系电话），不再有独立的 tenant 界面；
--     * 组织内身份不再用 account_tenant.role 表达，而是「角色」——角色新增 dept_id：
--         dept_id IS NULL → 平台级角色（全平台可用）
--         dept_id 非空    → 该部门（组织）的专属角色，例如「XX部主管」
--       部门还可通过 leader_account_id 指定负责人账号。
--
-- 幂等性（对存量库与新库都安全）：
--   1) 加列一律先查 information_schema 再用 PREPARE/EXECUTE 动态执行（MySQL 8 无 ADD COLUMN IF NOT EXISTS）；
--   2) 加菜单/授权用 INSERT IGNORE + 固定主键；
--   3) 回填用 UPDATE ... WHERE 条件收敛，重复执行结果一致。
--
-- 依赖：V9（gmd_faq_department / gmd_faq_role / gmd_faq_menu）、
--      V13（内容权限点）、V16（account_tenant 结构）。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 1. 部门表补充「组织资料」与「负责人账号」列 ----------
-- code                组织编码（原 gmd_faq_tenant.code 的语义）
-- leader_account_id   部门/组织负责人账号ID（关联 gmd_faq_account.id，可空）
-- description         组织描述/备注详情
-- contact_name        联系人姓名（原 tenant.contact_name）
-- contact_email       联系人邮箱（原 tenant.contact_email）
-- 说明：原有的 leader(varchar) / phone / email / remark 保留，作为「负责人姓名（文本）」「联系电话」等
--      非账号化的展示字段，避免破坏既有数据与页面。
SET @has_dept_code = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_department' AND COLUMN_NAME = 'code'
);
SET @sql = IF(@has_dept_code = 0,
  'ALTER TABLE `gmd_faq_department` ADD COLUMN `code` varchar(100) DEFAULT NULL COMMENT ''组织编码'' AFTER `name`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_dept_leader_account = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_department' AND COLUMN_NAME = 'leader_account_id'
);
SET @sql = IF(@has_dept_leader_account = 0,
  'ALTER TABLE `gmd_faq_department` ADD COLUMN `leader_account_id` bigint DEFAULT NULL COMMENT ''负责人账号ID（关联 gmd_faq_account.id）'' AFTER `leader`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_dept_description = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_department' AND COLUMN_NAME = 'description'
);
SET @sql = IF(@has_dept_description = 0,
  'ALTER TABLE `gmd_faq_department` ADD COLUMN `description` varchar(500) DEFAULT NULL COMMENT ''组织描述'' AFTER `sort_order`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_dept_contact_name = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_department' AND COLUMN_NAME = 'contact_name'
);
SET @sql = IF(@has_dept_contact_name = 0,
  'ALTER TABLE `gmd_faq_department` ADD COLUMN `contact_name` varchar(50) DEFAULT NULL COMMENT ''联系人姓名'' AFTER `description`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_dept_contact_email = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_department' AND COLUMN_NAME = 'contact_email'
);
SET @sql = IF(@has_dept_contact_email = 0,
  'ALTER TABLE `gmd_faq_department` ADD COLUMN `contact_email` varchar(100) DEFAULT NULL COMMENT ''联系人邮箱'' AFTER `contact_name`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 角色表新增 dept_id：NULL=平台级角色，非空=该部门（组织）的专属角色 ----------
SET @has_role_dept = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_role' AND COLUMN_NAME = 'dept_id'
);
SET @sql = IF(@has_role_dept = 0,
  'ALTER TABLE `gmd_faq_role` ADD COLUMN `dept_id` bigint DEFAULT NULL COMMENT ''所属部门（组织）ID，NULL=平台级角色'' AFTER `code`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 部门级角色的索引：查「某部门可用角色」时按 (tenant_id, dept_id) 过滤
SET @has_role_dept_idx = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_role' AND INDEX_NAME = 'idx_role_tenant_dept'
);
SET @sql = IF(@has_role_dept_idx = 0,
  'ALTER TABLE `gmd_faq_role` ADD INDEX `idx_role_tenant_dept` (`tenant_id`, `dept_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 内置角色数据范围降为「本部门及以下」----------
-- 「部门即组织」后，普通成员只应看到本组织（及其下级组织）的资源；
-- 平台管理员（superadmin / system_admin）保持 ALL，平台运维与资源治理仍需要全平台可见。
UPDATE `gmd_faq_role`
SET `data_scope` = 'DEPT_AND_CHILD'
WHERE `code` IN ('editor', 'viewer')
  AND `data_scope` = 'ALL';

-- ---------- 4. 存量账号回填所属部门（组织）----------
-- 口径：只处理「尚未设置部门」的账号；取其在 gmd_faq_account_tenant 中第一条成员关系的租户根部门。
-- 存量库里每个租户的根部门由 V9 建好（名称 = 租户名），因此这里用 parent_id = 0 定位。
-- 说明：新账号允许「无部门」（dept_id 为空）存在，本回填只针对迁移前的历史数据，
--      目的是让老账号在「组织 = 部门」的口径下不至于丧失数据可见范围。
UPDATE `gmd_faq_account` a
SET a.`dept_id` = (
  SELECT d.`id`
  FROM `gmd_faq_account_tenant` at
  JOIN `gmd_faq_department` d ON d.`tenant_id` = at.`tenant_id` AND d.`parent_id` = 0
  WHERE at.`account_id` = a.`id`
  ORDER BY d.`id`
  LIMIT 1
)
WHERE (a.`dept_id` IS NULL OR a.`dept_id` = 0)
  AND EXISTS (
    SELECT 1
    FROM `gmd_faq_account_tenant` at
    JOIN `gmd_faq_department` d ON d.`tenant_id` = at.`tenant_id` AND d.`parent_id` = 0
    WHERE at.`account_id` = a.`id`
  );

-- ---------- 5. 存量根部门回填组织编码：优先沿用原租户 code ----------
UPDATE `gmd_faq_department` d
JOIN `gmd_faq_tenant` t ON t.`id` = d.`tenant_id`
SET d.`code` = t.`code`,
    d.`description` = COALESCE(d.`description`, t.`description`),
    d.`contact_name` = COALESCE(d.`contact_name`, t.`contact_name`),
    d.`contact_email` = COALESCE(d.`contact_email`, t.`contact_email`)
WHERE d.`parent_id` = 0
  AND (d.`code` IS NULL OR d.`code` = '');

-- ---------- 6. 兜底：无任何角色的存量账号按所属部门补「业务查看者」 ----------
-- V13 已把 account_tenant 的旧枚举映射成内置角色，但只覆盖了当时存在成员关系的账号。
-- 「部门即组织」后权限完全走角色，没有角色的账号将看不到任何业务资源，这里补最小权限（viewer）。
INSERT IGNORE INTO `gmd_faq_role_account` (`id`, `role_id`, `account_id`, `tenant_id`)
SELECT (a.`id` & 0x0FFFFFFFFFFFFFFF) + 7000000000000000000, r.`id`, a.`id`, d.`tenant_id`
FROM `gmd_faq_account` a
JOIN `gmd_faq_department` d ON d.`id` = a.`dept_id`
JOIN `gmd_faq_role` r ON r.`tenant_id` = d.`tenant_id` AND r.`code` = 'viewer'
WHERE NOT EXISTS (
  SELECT 1 FROM `gmd_faq_role_account` ra WHERE ra.`account_id` = a.`id`
);

-- ---------- 7. 菜单：原「部门列表」文案归口到「组织与用户」 ----------
-- 页面语义变化：部门列表升级为「组织 / 部门」管理（组织资料 + 成员），用户列表成为唯一的账号管理入口。
-- 说明：本脚本**不新增权限点**——组织成员的拉入/移出复用既有的
-- system:user:list / system:user:edit 与 system:dept:list / system:dept:edit，
-- 避免出现「界面上勾不到、代码里也没判」的死权限点。
UPDATE `gmd_faq_menu` SET `name` = '组织与用户', `icon` = 'ApartmentOutlined', `sort_order` = 1
WHERE `id` = 1020 AND `menu_type` = 'MENU';

SET FOREIGN_KEY_CHECKS = 1;
