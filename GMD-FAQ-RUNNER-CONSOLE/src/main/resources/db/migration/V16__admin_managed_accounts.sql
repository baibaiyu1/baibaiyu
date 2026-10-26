-- ============================================================
-- 关闭自助注册 + 首次登录强制改密
-- ============================================================
-- 背景：
--   产品调整为「账号只能由组织管理员添加/删除」，因此：
--     1. 账号新增「必须先修改密码」标记：管理员新增用户、或管理员重置密码后置 1，
--        用户自行改密成功后由业务代码置回 0；
--     2. 下线「自助注册」及其配套的「发现组织 / 申请加入 / 审批」子系统，
--        相关表与列一并移除。
--
-- 幂等性：
--   * 加列前先查 information_schema.columns；
--   * 删列前先查 information_schema.columns；
--   * 删表使用 DROP TABLE IF EXISTS。
--   全部通过 PREPARE/EXECUTE 动态执行，可重复运行。
--
-- 注意：存量账号保持 must_change_password = 0（默认值），不会因为本迁移被迫改密。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 1) gmd_faq_account 增加 must_change_password ----------
SET @add_col := (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `gmd_faq_account` ADD COLUMN `must_change_password` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否必须先修改密码（管理员新增/重置密码后置1）'' AFTER `last_tenant_id`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'gmd_faq_account'
    AND COLUMN_NAME = 'must_change_password'
);
PREPARE stmt FROM @add_col;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2) 移除租户治理列（自助加入已下线） ----------
SET @drop_discoverable := (
  SELECT IF(
    COUNT(*) > 0,
    'ALTER TABLE `gmd_faq_tenant` DROP COLUMN `discoverable`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'gmd_faq_tenant'
    AND COLUMN_NAME = 'discoverable'
);
PREPARE stmt FROM @drop_discoverable;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_joinable := (
  SELECT IF(
    COUNT(*) > 0,
    'ALTER TABLE `gmd_faq_tenant` DROP COLUMN `joinable`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'gmd_faq_tenant'
    AND COLUMN_NAME = 'joinable'
);
PREPARE stmt FROM @drop_joinable;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_join_approval := (
  SELECT IF(
    COUNT(*) > 0,
    'ALTER TABLE `gmd_faq_tenant` DROP COLUMN `join_approval_required`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'gmd_faq_tenant'
    AND COLUMN_NAME = 'join_approval_required'
);
PREPARE stmt FROM @drop_join_approval;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 3) 删除加入申请表（子系统已下线） ----------
DROP TABLE IF EXISTS `gmd_faq_tenant_join_request`;
