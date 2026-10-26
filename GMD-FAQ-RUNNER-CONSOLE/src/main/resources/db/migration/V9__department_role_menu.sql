-- ============================================================
-- 系统设置：部门 / 角色 / 菜单 三张主表 + 两张关联表 + 账号部门归属列
-- ============================================================
-- 背景：
--   系统设置面板（左下角悬浮入口）需要「部门列表、角色配置、菜单管理」三块能力，
--   原先平台只有 account / tenant（组织即租户），缺少组织内部的部门、角色、菜单模型。
--   本脚本新增：
--     gmd_faq_department     部门（自关联树，租户隔离）
--     gmd_faq_role           角色（租户隔离，含数据权限范围 scope + 是否内置 builtin）
--     gmd_faq_menu           菜单（全局，含目录/菜单/按钮三类节点）
--     gmd_faq_role_menu      角色-菜单权限关联
--     gmd_faq_role_account   角色-账号关联（用户分配）
--   并给 gmd_faq_account 增加 dept_id（账号所属部门，可空）。
--
-- 幂等性（重要）：
--   本脚本同时对「存量库」与「新库（由 sql/db_init.sql 初始化）」两类环境执行，
--   且可能因环境已有部分结构而重复执行，因此：
--     1) 建表统一使用 CREATE TABLE IF NOT EXISTS；
--     2) 加列先查 information_schema 再用 PREPARE/EXECUTE 动态执行（MySQL 8 不支持
--        ADD COLUMN IF NOT EXISTS），已存在则空操作；
--     3) 内置数据统一用 INSERT IGNORE + 固定主键，重复执行不会产生重复行；
--     4) 菜单为全局数据（不按租户隔离），用 LEFT JOIN 判空后插入。
--
-- 依赖：本脚本不读取其它表数据做回填，可在 V1 基线之后任意位置执行。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 1. 部门表 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_department` (
  `id` bigint NOT NULL COMMENT '主键',
  `parent_id` bigint NOT NULL DEFAULT 0 COMMENT '父部门ID（0=顶级）',
  `name` varchar(100) NOT NULL COMMENT '部门名称',
  `leader` varchar(50) DEFAULT NULL COMMENT '负责人',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '显示顺序',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可用',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_department_tenant_parent` (`tenant_id`, `parent_id`),
  KEY `idx_department_tenant_enabled` (`tenant_id`, `enabled`)
) COMMENT='部门';

-- ---------- 2. 角色表 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_role` (
  `id` bigint NOT NULL COMMENT '主键',
  `name` varchar(100) NOT NULL COMMENT '角色名称',
  `code` varchar(100) NOT NULL COMMENT '角色权限字符',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '显示顺序',
  `data_scope` varchar(40) NOT NULL DEFAULT 'ALL' COMMENT '数据权限范围（ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF/DEPT_AND_CHILD_OR_SELF）',
  `builtin` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否内置角色（内置角色禁止删除）',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '角色状态',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_role_tenant_code` (`tenant_id`, `code`),
  KEY `idx_role_tenant_enabled` (`tenant_id`, `enabled`)
) COMMENT='角色';

-- ---------- 3. 菜单表（全局，不按租户隔离） ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_menu` (
  `id` bigint NOT NULL COMMENT '主键',
  `parent_id` bigint NOT NULL DEFAULT 0 COMMENT '父菜单ID（0=根目录）',
  `name` varchar(100) NOT NULL COMMENT '菜单名称',
  `menu_type` varchar(20) NOT NULL DEFAULT 'MENU' COMMENT '菜单类型（DIRECTORY=目录, MENU=菜单, BUTTON=按钮权限）',
  `path` varchar(200) DEFAULT NULL COMMENT '路由地址',
  `component` varchar(200) DEFAULT NULL COMMENT '组件路径',
  `perms` varchar(150) DEFAULT NULL COMMENT '权限字符',
  `icon` varchar(100) DEFAULT NULL COMMENT '菜单图标',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '显示顺序',
  `visible` tinyint(1) NOT NULL DEFAULT 1 COMMENT '显示状态',
  `cached` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否缓存',
  `external` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否外链',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '菜单状态（1=正常 0=停用）',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_menu_parent` (`parent_id`),
  KEY `idx_menu_type` (`menu_type`)
) COMMENT='菜单';

-- ---------- 4. 角色-菜单关联 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_role_menu` (
  `id` bigint NOT NULL COMMENT '主键',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
  KEY `idx_role_menu_role` (`role_id`)
) COMMENT='角色-菜单权限关联';

-- ---------- 5. 角色-账号关联（用户分配） ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_role_account` (
  `id` bigint NOT NULL COMMENT '主键',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `account_id` bigint NOT NULL COMMENT '账号ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_account` (`role_id`, `account_id`),
  KEY `idx_role_account_account` (`account_id`),
  KEY `idx_role_account_role` (`role_id`)
) COMMENT='角色-账号关联';

-- ---------- 6. 账号表增加所属部门列（MySQL 8 无 ADD COLUMN IF NOT EXISTS，先查后动态执行） ----------
SET @has_account_dept_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'gmd_faq_account'
    AND COLUMN_NAME = 'dept_id'
);
SET @add_account_dept_sql = IF(@has_account_dept_col = 0,
  'ALTER TABLE `gmd_faq_account` ADD COLUMN `dept_id` bigint DEFAULT NULL COMMENT ''所属部门ID''',
  'SELECT 1');
PREPARE add_account_dept_stmt FROM @add_account_dept_sql;
EXECUTE add_account_dept_stmt;
DEALLOCATE PREPARE add_account_dept_stmt;

-- ---------- 7. 内置菜单数据（固定主键，INSERT IGNORE 保证幂等） ----------
-- 目录：1000 系统管理 / 1100 租户管理 / 1200 系统监控
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (1000, 0, '系统管理', 'DIRECTORY', 'system',  NULL, 'system',        'SettingOutlined',   1, 1, 1, 0, 1, NULL),
  (1100, 0, '租户管理', 'DIRECTORY', 'tenant',  NULL, 'tenant',        'ClusterOutlined',   2, 1, 1, 0, 1, NULL),
  (1200, 0, '系统监控', 'DIRECTORY', 'monitor', NULL, 'monitor',       'MonitorOutlined',   3, 1, 1, 0, 1, NULL);

-- 菜单：系统管理下的功能页，与「系统设置」面板左侧导航一一对应
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (1010, 1000, '用户列表',   'MENU', 'user',      'settings/AllAccounts',       'system:user:list',      'TeamOutlined',       1, 1, 1, 0, 1, NULL),
  (1020, 1000, '部门列表',   'MENU', 'department','settings/Department',        'system:dept:list',      'ApartmentOutlined',  2, 1, 1, 0, 1, NULL),
  (1030, 1000, '角色配置',   'MENU', 'role',      'settings/Role',              'system:role:list',      'SafetyOutlined',     3, 1, 1, 0, 1, NULL),
  (1040, 1000, '菜单管理',   'MENU', 'menu',      'settings/Menu',              'system:menu:list',      'MenuOutlined',       4, 1, 1, 0, 1, NULL),
  (1050, 1000, '存储配置',   'MENU', 'storage',   'settings/StorageManagement', 'system:storage:list',   'DatabaseOutlined',   5, 1, 1, 0, 1, NULL),
  (1060, 1000, '参数配置',   'MENU', 'params',    'settings/SystemParams',      'system:params:list',    'ControlOutlined',    6, 1, 1, 0, 1, NULL),
  (1070, 1000, 'API Keys',   'MENU', 'apiKeys',   'settings/ApiKeys',           'system:apikey:list',    'KeyOutlined',        7, 1, 1, 0, 1, NULL);

-- 菜单：租户管理
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (1110, 1100, '租户列表',     'MENU', 'list',    'settings/TenantSettings',  'tenant:tenant:list',  'UnorderedListOutlined', 1, 1, 1, 0, 1, NULL),
  (1120, 1100, '租户套餐管理', 'MENU', 'package', 'settings/TenantSettings',  'tenant:package:list', 'GiftOutlined',          2, 1, 1, 0, 1, NULL);

-- 菜单：系统监控
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (1210, 1200, '在线用户', 'MENU', 'online', 'settings/ExecutionNodes', 'monitor:online:list', 'WifiOutlined',  1, 1, 1, 0, 1, NULL),
  (1220, 1200, '缓存监控', 'MENU', 'cache',  'settings/ExecutionNodes', 'monitor:cache:list',  'FundOutlined',  2, 1, 1, 0, 1, NULL);

-- 按钮权限：用户列表
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1011, 1010, '用户新增', 'BUTTON', 'system:user:add',    1, 1),
  (1012, 1010, '用户修改', 'BUTTON', 'system:user:edit',   2, 1),
  (1013, 1010, '用户删除', 'BUTTON', 'system:user:remove', 3, 1),
  (1014, 1010, '用户导出', 'BUTTON', 'system:user:export', 4, 1),
  (1015, 1010, '重置密码', 'BUTTON', 'system:user:resetPwd', 5, 1);

-- 按钮权限：部门列表
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1021, 1020, '部门新增', 'BUTTON', 'system:dept:add',    1, 1),
  (1022, 1020, '部门修改', 'BUTTON', 'system:dept:edit',   2, 1),
  (1023, 1020, '部门删除', 'BUTTON', 'system:dept:remove', 3, 1);

-- 按钮权限：角色配置
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1031, 1030, '角色新增',     'BUTTON', 'system:role:add',           1, 1),
  (1032, 1030, '角色修改',     'BUTTON', 'system:role:edit',          2, 1),
  (1033, 1030, '角色删除',     'BUTTON', 'system:role:remove',        3, 1),
  (1034, 1030, '分配数据权限', 'BUTTON', 'system:role:dataScope',     4, 1),
  (1035, 1030, '分配用户',     'BUTTON', 'system:role:assignUser',    5, 1);

-- 按钮权限：菜单管理
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1041, 1040, '菜单新增', 'BUTTON', 'system:menu:add',    1, 1),
  (1042, 1040, '菜单修改', 'BUTTON', 'system:menu:edit',   2, 1),
  (1043, 1040, '菜单删除', 'BUTTON', 'system:menu:remove', 3, 1);

-- 按钮权限：存储配置
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1051, 1050, '存储新增', 'BUTTON', 'system:storage:add',    1, 1),
  (1052, 1050, '存储修改', 'BUTTON', 'system:storage:edit',   2, 1),
  (1053, 1050, '存储删除', 'BUTTON', 'system:storage:remove', 3, 1);

-- 按钮权限：参数配置
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1061, 1060, '参数新增', 'BUTTON', 'system:params:add',    1, 1),
  (1062, 1060, '参数修改', 'BUTTON', 'system:params:edit',   2, 1),
  (1063, 1060, '参数删除', 'BUTTON', 'system:params:remove', 3, 1);

-- 按钮权限：API Keys
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1071, 1070, 'API Key 新增', 'BUTTON', 'system:apikey:add',    1, 1),
  (1072, 1070, 'API Key 修改', 'BUTTON', 'system:apikey:edit',   2, 1),
  (1073, 1070, 'API Key 删除', 'BUTTON', 'system:apikey:remove', 3, 1);

-- ---------- 8. 存量租户补齐内置部门与角色（INSERT ... SELECT，天然幂等） ----------
-- 8.1 每个租户补一个根部门：名称取租户名，主键为 9000000000000000000 + tenant_id。
--     说明：这里牺牲一点主键可读性换取「跨租户唯一且可重复执行」，
--     应用层后续新增部门走 MyBatis-Plus 雪花 ID，不会与该区间冲突。
INSERT IGNORE INTO `gmd_faq_department`
  (`id`, `parent_id`, `name`, `sort_order`, `remark`, `enabled`, `tenant_id`)
SELECT 9000000000000000000 + t.`id`, 0, t.`name`, 0, '系统初始化根部门', 1, t.`id`
FROM `gmd_faq_tenant` t
WHERE NOT EXISTS (
  SELECT 1 FROM `gmd_faq_department` d
  WHERE d.`tenant_id` = t.`id` AND d.`parent_id` = 0 AND d.`name` = t.`name`
);

-- 8.2 每个租户补两个内置角色：超级管理员 / 系统超级管理员
--     主键分别为 9100000000000000000 + tenant_id、9200000000000000000 + tenant_id。
INSERT IGNORE INTO `gmd_faq_role`
  (`id`, `name`, `code`, `sort_order`, `data_scope`, `builtin`, `remark`, `enabled`, `tenant_id`)
SELECT 9100000000000000000 + t.`id`, '超级管理员', 'superadmin', 1, 'ALL', 1, '系统内置角色，拥有全部权限', 1, t.`id`
FROM `gmd_faq_tenant` t
WHERE NOT EXISTS (
  SELECT 1 FROM `gmd_faq_role` r WHERE r.`tenant_id` = t.`id` AND r.`code` = 'superadmin'
);

INSERT IGNORE INTO `gmd_faq_role`
  (`id`, `name`, `code`, `sort_order`, `data_scope`, `builtin`, `remark`, `enabled`, `tenant_id`)
SELECT 9200000000000000000 + t.`id`, '系统超级管理员', 'system_admin', 2, 'ALL', 1, '系统内置角色，拥有全部权限', 1, t.`id`
FROM `gmd_faq_tenant` t
WHERE NOT EXISTS (
  SELECT 1 FROM `gmd_faq_role` r WHERE r.`tenant_id` = t.`id` AND r.`code` = 'system_admin'
);

-- 8.3 内置角色默认勾选全部菜单权限（INSERT ... SELECT 配合唯一键，可重复执行）
-- 注意：id 直接相加会超出 bigint 范围（雪花 ID + 雪花 ID ≈ 2.2e18 已接近 9.22e18 上限），
-- 故统一用 & 0x3FFFFFFFFFFFFFFF 收敛到 4.6e18 以内；role_menu 有 (role_id, menu_id) 唯一键，
-- 即便极端情况下 id 撞车也只会被 INSERT IGNORE 跳过，不会破坏唯一性约束。
INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`builtin` = 1;

-- 8.4 存量租户拥有者/管理员自动分配到本租户「超级管理员」角色（仅当尚未分配任何角色时）
--     取 account_id 作为主键：雪花 ID 高位为同一时间戳，可保证同租户内唯一；
--     先与 0x0FFFFFFFFFFFFFFF 按位与把取值压到 1.15e18 以内，避免与 role_id 相加时 bigint 溢出。
INSERT IGNORE INTO `gmd_faq_role_account` (`id`, `role_id`, `account_id`, `tenant_id`)
SELECT at.`account_id` & 0x0FFFFFFFFFFFFFFF, r.`id`, at.`account_id`, at.`tenant_id`
FROM `gmd_faq_account_tenant` at
JOIN `gmd_faq_role` r
  ON r.`tenant_id` = at.`tenant_id` AND r.`code` = 'superadmin'
WHERE at.`role` IN ('TENANT_OWNER', 'TENANT_ADMIN')
  AND NOT EXISTS (
    SELECT 1 FROM `gmd_faq_role_account` ra
    WHERE ra.`account_id` = at.`account_id` AND ra.`tenant_id` = at.`tenant_id`
  );

SET FOREIGN_KEY_CHECKS = 1;
