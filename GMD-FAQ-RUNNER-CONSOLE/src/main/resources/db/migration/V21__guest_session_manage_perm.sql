-- ============================================================
-- 游客会话（分享 chatKey 链接产生的会话）文件管理权限点
-- ============================================================
-- 背景（为什么需要这个脚本）：
--   智能体可以通过「分享链接」对外提供游客对话入口（AccountServiceImpl#chatKeyToken）。
--   该入口每次调用都会用 IdWorker.getId() 现场造一个随机 userId 建立会话，
--   这个 userId 在 gmd_faq_account 里**没有对应账号**，因此这类会话：
--     1) 不会出现在任何登录用户的会话列表里（列表按 user_id 过滤）；
--     2) 其工作空间目录 .apboa/tenants/{tenantCode}/workspaces/{sessionId} 会长期占盘；
--     3) 目前完全没有清理入口，也没有任何定时任务回收。
--
--   本脚本只做一件事：种下「游客会话管理」权限点，供会话文件管理页的
--   「会话来源 = 游客」筛选与清理接口判权。接口侧用 @PermNeed 拦截，
--   因此普通用户（editor / viewer）**不会**获得该权限，做到默认管理员专属、
--   同时保留在角色授权界面单独勾选的能力。
--
-- 幂等性（对存量库与新库都安全）：
--   菜单沿用固定主键 3112 + INSERT IGNORE；授权沿用 V13 的
--   (role_id + menu_id) & 0x3FFFFFFFFFFFFFFF 主键 + INSERT IGNORE 双重兜底。
--
-- 依赖：V9（gmd_faq_menu / gmd_faq_role / gmd_faq_role_menu）、V13（3100 智能体菜单与内置角色）。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 1. 权限点：挂在「智能体」菜单（3100）下 ----------
-- 默认只授权给 superadmin / system_admin：游客会话跨用户可见且清理不可恢复，
-- 不能随 editor「业务域全部权限」的批量授权自动下发（V13 的 editor 规则是
-- 「perms 不以 system:/tenant:/monitor:/ops: 开头就发」，本权限点若不加白名单会被误发）。
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (3112, 3100, '游客会话管理', 'BUTTON', 'agent:chat:guest:manage', 12, 1);

-- ---------- 2. 授权：仅平台管理员 ----------
INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`builtin` = 1
  AND r.`code` IN ('superadmin', 'system_admin')
  AND m.`id` = 3112;

-- ---------- 3. 存量保护：确保业务角色不持有该权限点 ----------
-- 迁移顺序保证 V13 执行时 3112 尚不存在，所以全新库与常规存量库都不会把它发给 editor。
-- 但这条显式收回仍然必要，原因有二：
--   1) V13 的 editor 规则是黑名单式放行（「不以 system:/tenant:/monitor:/ops: 开头就发」），
--      任何在 V20 之后新增业务权限点的脚本都必须显式处理，这里先把口径立住；
--   2) 有人手工给 editor 勾过该权限点时，升级后会回到「默认管理员专属」的安全基线。
-- 只删 editor / viewer：superadmin / system_admin 的授权在上一步，不受影响。
-- 用子查询而不是 DELETE ... JOIN：本仓迁移里没有多表删除的先例，子查询形式更保守。
DELETE FROM `gmd_faq_role_menu`
WHERE `menu_id` = 3112
  AND `role_id` IN (
    SELECT `id` FROM (
      SELECT `id` FROM `gmd_faq_role`
      WHERE `builtin` = 1 AND `code` IN ('editor', 'viewer')
    ) AS business_roles
  );
