-- ============================================================
-- 内置角色数据范围调整：editor / viewer 改为「全部数据权限」
-- ============================================================
-- 背景：
--   数据权限范围（DataScope）已叠加进资源可见性过滤（与本迁移同批上线）：
--   SELF=只看归属人是自己的资源、DEPT=本部门、DEPT_AND_CHILD=本部门及下级、ALL=不限。
--
--   V13 给内置业务角色设的默认值是 editor=DEPT_AND_CHILD、viewer=SELF，
--   但这两个角色是「业务可编辑 / 业务只读」，其可写性已经由权限点（perms）表达：
--     viewer 没有 add/edit/remove 权限点 → 天然只读。
--   若再叠加 SELF，viewer 打开资源列表会**什么都看不到**（只看得到自己创建的），
--   等于把「只读」误做成「看不见」，与角色语义不符。
--
--   因此本迁移把内置的 editor / viewer 数据范围统一为 ALL：
--     * 只读能力由 perms 控制；
--     * 数据范围留给「需要按部门收窄」的自定义角色使用（角色配置 → 分配数据权限）。
--
-- 幂等性：只更新 builtin=1 且 code 命中且当前值不为 ALL 的行，可重复执行。
-- ============================================================

SET NAMES utf8mb4;

UPDATE `gmd_faq_role`
SET `data_scope` = 'ALL', `updated_at` = NOW()
WHERE `builtin` = 1
  AND `code` IN ('editor', 'viewer')
  AND `data_scope` <> 'ALL';
