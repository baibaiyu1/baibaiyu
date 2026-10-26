-- ============================================================
-- 平台管理员权限点（单轨权限）：system:admin
-- ============================================================
-- 背景：
--   切换单轨权限后，后端原先用 UserUtils.isAdmin()（account_tenant.role ∈ TENANT_ADMIN/OWNER）
--   做兜底判断的地方（资源可见性、审批审批人、Dashboard、智能体启停等）需要一个明确的权限点。
--   这里新增 system:admin 权限点，只授予内置角色 superadmin / system_admin；
--   内置角色 editor / viewer 不包含它（V13 的授权规则已排除 system:% 前缀，天然不会勾选到）。
--
-- 幂等性：
--   1) 菜单/按钮固定主键 + INSERT IGNORE；
--   2) 角色授权用 (role_id + menu_id) & 0x3FFFFFFFFFFFFFFF 生成主键 + INSERT IGNORE，撞唯一键也只会被跳过。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 权限点：挂在「系统管理」目录下（1000，V9 已存在）
INSERT IGNORE INTO `gmd_faq_menu` (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`) VALUES
  (1001, 1000, '平台管理员', 'BUTTON', 'system:admin', 0, 1);

-- 2. 授权：仅内置角色 superadmin / system_admin
INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`builtin` = 1 AND r.`code` IN ('superadmin', 'system_admin') AND m.`id` = 1001;

SET FOREIGN_KEY_CHECKS = 1;
