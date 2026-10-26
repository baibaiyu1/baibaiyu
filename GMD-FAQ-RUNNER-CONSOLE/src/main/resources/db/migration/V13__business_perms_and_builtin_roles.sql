-- ============================================================
-- 单轨权限：业务域菜单与按钮 perms + 内置角色扩展 + 存量账号角色映射
-- ============================================================
-- 背景（为什么需要这个脚本）：
--   V9 只种了「系统管理 / 租户管理 / 系统监控」三组菜单与按钮 perms；
--   业务域（工作台 / 智能体 / 工作流 / 自动化 / API服务 / 知识库 / 模型 / 技能 /
--   MCP / 工具 / 扩展 / 提示词 / 敏感词 / 审批 / 运维）**一条 perms 都没有**。
--
--   本平台已决定把授权从「account_tenant.role 旧枚举（TENANT_OWNER/ADMIN/EDITOR/VIEWER）」
--   彻底切到「角色 → 菜单/按钮 perms」单轨（`role_account` → `role_menu` → `menu.perms`）。
--   切换后所有鉴权都按 perms 判定，因此必须先把业务域权限点补齐，
--   否则业务页面与管理接口在切换后无权限可判（要么全放开、要么全拒绝）。
--
-- 幂等性（对存量库与新库都安全）：
--   1) 菜单/按钮沿用 V9 的固定主键 + INSERT IGNORE；
--   2) 新内置角色 editor/viewer 主键取 8000000000000000000/8100000000000000000 + tenant_id，
--      INSERT ... SELECT + NOT EXISTS 兜底；
--      【重要】不能用 93/94 前缀：BIGINT 上限是 9223372036854775807，
--      9300000000000000000 + tenant_id 会直接溢出（实测 MySQL 报
--      `BIGINT value is out of range`，且 INSERT ... SELECT 会把溢出结果夹成
--      BIGINT 最大值 9223372036854775807 造成脏数据）。V9 的 91/92 前缀已是安全上限，
--      因此新角色改用 80/81 前缀（远高于雪花ID当前量级 ~1.9e18，且与部门 90 前缀错开）。
--   3) 角色授权用 (role_id + menu_id) & 0x3FFFFFFFFFFFFFFF 生成主键 + INSERT IGNORE，
--      role_menu 有 (role_id, menu_id) 唯一键，撞车主键也只会被跳过；
--   4) 存量账号映射先各自掩码再相加，避免 account_id(雪花 ~2e18) + role_id(8e18) 溢出；
--      且只对「该租户下尚无任何角色」的账号生效，不覆盖已授权数据。
--
-- 依赖：V9（gmd_faq_menu / gmd_faq_role / gmd_faq_role_menu / gmd_faq_role_account）、
--      gmd_faq_account_tenant（旧枚举字段用于一次性映射）。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 1. 业务域目录与页面菜单（perms 命名沿用 V9 的 module:resource:action） ----------
-- 目录：2000 空间 / 3000 开发 / 4000 资源 / 5000 平台运维，与前端侧边栏分类一一对应
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (2000, 0, '空间',     'DIRECTORY', 'space',    NULL, 'space',    'HomeOutlined',      1, 1, 1, 0, 1, NULL),
  (3000, 0, '开发',     'DIRECTORY', 'dev',      NULL, 'dev',      'CodeOutlined',      2, 1, 1, 0, 1, NULL),
  (4000, 0, '资源',     'DIRECTORY', 'resource', NULL, 'resource', 'AppstoreOutlined',  3, 1, 1, 0, 1, NULL),
  (5000, 0, '平台运维', 'DIRECTORY', 'ops',      NULL, 'ops',      'DeploymentUnitOutlined', 4, 1, 1, 0, 1, NULL);

-- 空间
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (2010, 2000, '工作台', 'MENU', 'dashboard', 'Dashboard/index', 'dashboard:view', 'DashboardOutlined', 1, 1, 1, 0, 1, NULL);

-- 开发
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (3100, 3000, '智能体',   'MENU', 'agent',       'Agent/index',        'agent:definition:list',  'RobotOutlined',      1, 1, 1, 0, 1, NULL),
  (3200, 3000, '工作流',   'MENU', 'workflow',    'Workflow/index',     'workflow:definition:list', 'PartitionOutlined', 2, 1, 1, 0, 1, NULL),
  (3300, 3000, '自动化',   'MENU', 'automation',  'Automation/index',   'automation:job:list',    'ThunderboltOutlined', 3, 1, 1, 0, 1, NULL),
  (3400, 3000, 'API服务',  'MENU', 'api-service', 'ApiService/index',   'apiservice:app:list',    'ApiOutlined',        4, 1, 1, 0, 1, NULL),
  (3500, 3000, '知识库',   'MENU', 'knowledge',   'Knowledge/index',    'knowledge:config:list',  'BookOutlined',       5, 1, 1, 0, 1, NULL);

-- 资源
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (4100, 4000, '模型',     'MENU', 'model',     'Model/index',     'model:provider:list',  'CloudOutlined',   1, 1, 1, 0, 1, NULL),
  (4200, 4000, '技能',     'MENU', 'skill',     'Skill/index',     'skill:package:list',   'ToolOutlined',    2, 1, 1, 0, 1, NULL),
  (4300, 4000, 'MCP',      'MENU', 'mcp',       'Mcp/index',       'mcp:server:list',      'ClusterOutlined', 3, 1, 1, 0, 1, NULL),
  (4400, 4000, '工具',     'MENU', 'tool',      'Tool/index',      'tool:config:list',     'BuildOutlined',   4, 1, 1, 0, 1, NULL),
  (4500, 4000, '扩展',     'MENU', 'hook',      'Hook/index',      'hook:config:list',     'ExtensionOutlined', 5, 1, 1, 0, 1, NULL),
  (4600, 4000, '提示词',   'MENU', 'prompt',    'Prompt/index',    'prompt:template:list', 'FileTextOutlined', 6, 1, 1, 0, 1, NULL),
  (4700, 4000, '敏感词',   'MENU', 'sensitive', 'Sensitive/index', 'sensitive:word:list',  'FilterOutlined',  7, 1, 1, 0, 1, NULL),
  (4010, 4000, '资源治理', 'MENU', 'governance', NULL,             'resource:governance:list', 'SafetyCertificateOutlined', 8, 1, 1, 0, 1, '资源可见性/Fork/收藏的权限归集，前端入口在各资源卡片菜单上');

-- 平台运维
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_order`, `visible`, `cached`, `external`, `status`, `remark`) VALUES
  (5100, 5000, '审批工作台', 'MENU', 'review',      'Review/Agent',   'approval:request:list', 'AuditOutlined',    1, 1, 1, 0, 1, NULL),
  (5200, 5000, '运行时监控', 'MENU', 'ops/monitor', 'Ops/Monitor',    'ops:monitor:view',      'MonitorOutlined',  2, 1, 1, 0, 1, NULL),
  (5300, 5000, '存储管理',   'MENU', 'ops/storage', 'Ops/Storage',    'ops:storage:view',      'DatabaseOutlined', 3, 1, 1, 0, 1, NULL);

-- ---------- 2. 业务域按钮权限 ----------
-- 智能体
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3101, 3100, '智能体新增',     'BUTTON', 'agent:definition:add',     1, 1),
  (3102, 3100, '智能体编辑',     'BUTTON', 'agent:definition:edit',    2, 1),
  (3103, 3100, '智能体删除',     'BUTTON', 'agent:definition:remove',  3, 1),
  (3104, 3100, '智能体启停',     'BUTTON', 'agent:definition:enable',  4, 1),
  (3105, 3100, '智能体发布审批', 'BUTTON', 'agent:definition:publish', 5, 1),
  (3106, 3100, '智能体 Fork',    'BUTTON', 'agent:definition:fork',    6, 1),
  (3107, 3100, '智能体运行',     'BUTTON', 'agent:definition:run',     7, 1),
  (3108, 3100, '代码执行配置',   'BUTTON', 'agent:code:edit',          8, 1),
  (3109, 3100, '长期记忆配置',   'BUTTON', 'agent:memory:edit',        9, 1),
  (3110, 3100, '编排工作台配置', 'BUTTON', 'agent:studio:edit',       10, 1);

-- 工作流
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3201, 3200, '工作流新增', 'BUTTON', 'workflow:definition:add',    1, 1),
  (3202, 3200, '工作流编辑', 'BUTTON', 'workflow:definition:edit',   2, 1),
  (3203, 3200, '工作流删除', 'BUTTON', 'workflow:definition:remove', 3, 1),
  (3204, 3200, '工作流运行', 'BUTTON', 'workflow:definition:run',    4, 1),
  (3205, 3200, '工作流发布', 'BUTTON', 'workflow:definition:publish',5, 1);

-- 自动化
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3301, 3300, '任务新增',   'BUTTON', 'automation:job:add',     1, 1),
  (3302, 3300, '任务编辑',   'BUTTON', 'automation:job:edit',    2, 1),
  (3303, 3300, '任务删除',   'BUTTON', 'automation:job:remove',  3, 1),
  (3304, 3300, '任务执行',   'BUTTON', 'automation:job:run',     4, 1),
  (3305, 3300, '任务记录',   'BUTTON', 'automation:record:list', 5, 1);

-- API 服务
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3401, 3400, '应用新增', 'BUTTON', 'apiservice:app:add',    1, 1),
  (3402, 3400, '应用编辑', 'BUTTON', 'apiservice:app:edit',   2, 1),
  (3403, 3400, '应用删除', 'BUTTON', 'apiservice:app:remove', 3, 1);

-- 知识库
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3501, 3500, '知识库新增',   'BUTTON', 'knowledge:config:add',    1, 1),
  (3502, 3500, '知识库编辑',   'BUTTON', 'knowledge:config:edit',   2, 1),
  (3503, 3500, '知识库删除',   'BUTTON', 'knowledge:config:remove', 3, 1),
  (3504, 3500, '文档上传',     'BUTTON', 'knowledge:doc:add',       4, 1),
  (3505, 3500, '文档编辑',     'BUTTON', 'knowledge:doc:edit',      5, 1),
  (3506, 3500, '文档删除',     'BUTTON', 'knowledge:doc:remove',    6, 1);

-- 模型
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4101, 4100, '供应商新增', 'BUTTON', 'model:provider:add',    1, 1),
  (4102, 4100, '供应商编辑', 'BUTTON', 'model:provider:edit',   2, 1),
  (4103, 4100, '供应商删除', 'BUTTON', 'model:provider:remove', 3, 1),
  (4104, 4100, '模型新增',   'BUTTON', 'model:config:add',      4, 1),
  (4105, 4100, '模型编辑',   'BUTTON', 'model:config:edit',     5, 1),
  (4106, 4100, '模型删除',   'BUTTON', 'model:config:remove',   6, 1);

-- 技能
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4201, 4200, '技能新增',     'BUTTON', 'skill:package:add',    1, 1),
  (4202, 4200, '技能编辑',     'BUTTON', 'skill:package:edit',   2, 1),
  (4203, 4200, '技能删除',     'BUTTON', 'skill:package:remove', 3, 1),
  (4204, 4200, '技能导入',     'BUTTON', 'skill:package:import', 4, 1),
  (4205, 4200, '技能文件编辑', 'BUTTON', 'skill:file:edit',      5, 1),
  (4206, 4200, '技能市场导入', 'BUTTON', 'skill:hub:import',     6, 1);

-- MCP
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4301, 4300, 'MCP 新增',       'BUTTON', 'mcp:server:add',      1, 1),
  (4302, 4300, 'MCP 编辑',       'BUTTON', 'mcp:server:edit',     2, 1),
  (4303, 4300, 'MCP 删除',       'BUTTON', 'mcp:server:remove',   3, 1),
  (4304, 4300, 'MCP 调试',       'BUTTON', 'mcp:server:debug',    4, 1),
  (4305, 4300, 'MCP 激活',       'BUTTON', 'mcp:server:activate', 5, 1),
  (4306, 4300, 'MCP 工具同步',   'BUTTON', 'mcp:server:sync',     6, 1),
  (4307, 4300, 'MCP 工具治理',   'BUTTON', 'mcp:tool:edit',       7, 1),
  (4308, 4300, 'MCP 对外发布',   'BUTTON', 'mcp:server:publish',  8, 1);

-- 工具
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4401, 4400, '工具新增',       'BUTTON', 'tool:config:add',      1, 1),
  (4402, 4400, '工具编辑',       'BUTTON', 'tool:config:edit',     2, 1),
  (4403, 4400, '工具删除',       'BUTTON', 'tool:config:remove',   3, 1),
  (4404, 4400, '工具调试',       'BUTTON', 'tool:config:debug',    4, 1),
  (4405, 4400, 'OpenAPI 导入',   'BUTTON', 'tool:openapi:import',  5, 1);

-- 扩展
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4501, 4500, '扩展新增', 'BUTTON', 'hook:config:add',    1, 1),
  (4502, 4500, '扩展编辑', 'BUTTON', 'hook:config:edit',   2, 1),
  (4503, 4500, '扩展删除', 'BUTTON', 'hook:config:remove', 3, 1);

-- 提示词
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4601, 4600, '提示词新增', 'BUTTON', 'prompt:template:add',    1, 1),
  (4602, 4600, '提示词编辑', 'BUTTON', 'prompt:template:edit',   2, 1),
  (4603, 4600, '提示词删除', 'BUTTON', 'prompt:template:remove', 3, 1);

-- 敏感词
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4701, 4700, '敏感词新增', 'BUTTON', 'sensitive:word:add',    1, 1),
  (4702, 4700, '敏感词编辑', 'BUTTON', 'sensitive:word:edit',   2, 1),
  (4703, 4700, '敏感词删除', 'BUTTON', 'sensitive:word:remove', 3, 1);

-- 资源治理（可见性管理为管理员级权限，独立成点便于授权时单独勾选）
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (4011, 4010, '修改资源可见性', 'BUTTON', 'resource:visibility:manage', 1, 1);

-- 审批
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (5101, 5100, '审批流配置', 'BUTTON', 'approval:flow:config',      1, 1),
  (5102, 5100, '审批处理',   'BUTTON', 'approval:request:approve',  2, 1);

-- 运维
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (5201, 5200, '运行时总览', 'BUTTON', 'ops:monitor:export', 1, 1),
  (5301, 5300, '清理工作空间', 'BUTTON', 'ops:storage:clear', 1, 1);

-- 租户管理：V9 只种了 tenant:tenant:list / tenant:package:list，其余动作（改/删/成员/加入审批）需要补齐，
-- 否则切换单轨后 TenantController 上这 9 个接口无权限可判。
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1111, 1110, '租户修改',     'BUTTON', 'tenant:tenant:edit',          1, 1),
  (1112, 1110, '租户删除',     'BUTTON', 'tenant:tenant:remove',        2, 1),
  (1113, 1110, '租户成员管理', 'BUTTON', 'tenant:tenant:member',        3, 1),
  (1114, 1110, '加入申请审批', 'BUTTON', 'tenant:tenant:join:approve',  4, 1);

-- 补充：文档查看 / 技能市场查看 / 长期记忆查看（这几个是只读动作，viewer 角色需要）
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3507, 3500, '文档查看',     'BUTTON', 'knowledge:doc:list',  7, 1),
  (4207, 4200, '技能市场查看', 'BUTTON', 'skill:hub:list',      7, 1),
  (3111, 3100, '长期记忆查看', 'BUTTON', 'agent:memory:list',  11, 1);

-- ---------- 3. 内置角色扩展：editor（业务可编辑） / viewer（业务只读） ----------
-- 说明：V9 的 superadmin / system_admin 拥有全部菜单，切换单轨后仍是「平台管理员」；
--       旧枚举 TENANT_EDITOR / TENANT_VIEWER 在存量库中没有对应角色，
--       若不补建角色并映射，这批账号切换后会因为 perms 为空而失去全部业务访问能力。
INSERT IGNORE INTO `gmd_faq_role`
  (`id`, `name`, `code`, `sort_order`, `data_scope`, `builtin`, `remark`, `enabled`, `tenant_id`)
SELECT 8000000000000000000 + t.`id`, '业务编辑者', 'editor', 3, 'DEPT_AND_CHILD', 1,
       '系统内置角色，拥有业务域全部权限（不含系统设置与运维）', 1, t.`id`
FROM `gmd_faq_tenant` t
WHERE NOT EXISTS (
  SELECT 1 FROM `gmd_faq_role` r WHERE r.`tenant_id` = t.`id` AND r.`code` = 'editor'
);

INSERT IGNORE INTO `gmd_faq_role`
  (`id`, `name`, `code`, `sort_order`, `data_scope`, `builtin`, `remark`, `enabled`, `tenant_id`)
SELECT 8100000000000000000 + t.`id`, '业务查看者', 'viewer', 4, 'SELF', 1,
       '系统内置角色，仅业务域只读权限', 1, t.`id`
FROM `gmd_faq_tenant` t
WHERE NOT EXISTS (
  SELECT 1 FROM `gmd_faq_role` r WHERE r.`tenant_id` = t.`id` AND r.`code` = 'viewer'
);

-- ---------- 4. 角色授权（幂等：主键与 (role_id, menu_id) 唯一键双重兜底） ----------
-- 4.1 superadmin / system_admin：补齐本脚本新增的全部菜单与按钮
INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`builtin` = 1 AND r.`code` IN ('superadmin', 'system_admin');

-- 4.2 editor：业务域全部菜单与按钮（排除系统设置 / 租户管理 / 监控 / 运维 / 可见性管理）
INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`code` = 'editor' AND r.`builtin` = 1
  AND (
    m.`perms` IS NULL
    OR (
      m.`perms` NOT LIKE 'system:%'
      AND m.`perms` NOT LIKE 'tenant:%'
      AND m.`perms` NOT LIKE 'monitor:%'
      AND m.`perms` NOT LIKE 'ops:%'
      AND m.`perms` <> 'resource:visibility:manage'
      AND m.`perms` <> 'approval:flow:config'
      AND m.`perms` <> 'approval:request:approve'
    )
  )
  AND m.`id` >= 1000;

-- 4.3 viewer：业务域只读（页面列表权限 + 目录节点），不含系统设置 / 租户管理 / 监控 / 运维
INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`code` = 'viewer' AND r.`builtin` = 1
  AND (
    m.`perms` LIKE '%:list'
    OR m.`perms` LIKE '%:view'
    OR m.`perms` IN ('space', 'dev', 'resource')
  )
  AND m.`perms` NOT LIKE 'system:%'
  AND m.`perms` NOT LIKE 'tenant:%'
  AND m.`perms` NOT LIKE 'monitor:%'
  AND m.`perms` NOT LIKE 'ops:%';

-- ---------- 5. 存量账号的角色映射（旧枚举 → 新角色，仅在该租户下尚无任何角色时执行） ----------
-- 先掩码再相加：account_id 是雪花ID（~2e18），role_id 为 8e18 量级，直接相加会超出 BIGINT 上限。
-- 5.1 TENANT_EDITOR → editor
INSERT IGNORE INTO `gmd_faq_role_account` (`id`, `role_id`, `account_id`, `tenant_id`)
SELECT ((at.`account_id` & 0x0FFFFFFFFFFFFFFF) + (r.`id` & 0x00FFFFFFFFFFFFFF)) & 0x3FFFFFFFFFFFFFFF,
       r.`id`, at.`account_id`, at.`tenant_id`
FROM `gmd_faq_account_tenant` at
JOIN `gmd_faq_role` r ON r.`tenant_id` = at.`tenant_id` AND r.`code` = 'editor' AND r.`builtin` = 1
WHERE at.`role` = 'TENANT_EDITOR'
  AND NOT EXISTS (
    SELECT 1 FROM `gmd_faq_role_account` ra
    WHERE ra.`account_id` = at.`account_id` AND ra.`tenant_id` = at.`tenant_id`
  );

-- 5.2 TENANT_VIEWER → viewer
INSERT IGNORE INTO `gmd_faq_role_account` (`id`, `role_id`, `account_id`, `tenant_id`)
SELECT ((at.`account_id` & 0x0FFFFFFFFFFFFFFF) + (r.`id` & 0x00FFFFFFFFFFFFFF)) & 0x3FFFFFFFFFFFFFFF,
       r.`id`, at.`account_id`, at.`tenant_id`
FROM `gmd_faq_account_tenant` at
JOIN `gmd_faq_role` r ON r.`tenant_id` = at.`tenant_id` AND r.`code` = 'viewer' AND r.`builtin` = 1
WHERE at.`role` = 'TENANT_VIEWER'
  AND NOT EXISTS (
    SELECT 1 FROM `gmd_faq_role_account` ra
    WHERE ra.`account_id` = at.`account_id` AND ra.`tenant_id` = at.`tenant_id`
  );

-- 5.3 兜底：仍无任何角色的账号（例如账号在其他渠道创建）映射为 viewer，
--     保证切换单轨后不存在「perms 为空、完全无法访问」的账号。
INSERT IGNORE INTO `gmd_faq_role_account` (`id`, `role_id`, `account_id`, `tenant_id`)
SELECT ((at.`account_id` & 0x0FFFFFFFFFFFFFFF) + (r.`id` & 0x00FFFFFFFFFFFFFF)) & 0x3FFFFFFFFFFFFFFF,
       r.`id`, at.`account_id`, at.`tenant_id`
FROM `gmd_faq_account_tenant` at
JOIN `gmd_faq_role` r ON r.`tenant_id` = at.`tenant_id` AND r.`code` = 'viewer' AND r.`builtin` = 1
WHERE NOT EXISTS (
    SELECT 1 FROM `gmd_faq_role_account` ra
    WHERE ra.`account_id` = at.`account_id` AND ra.`tenant_id` = at.`tenant_id`
  );

SET FOREIGN_KEY_CHECKS = 1;
