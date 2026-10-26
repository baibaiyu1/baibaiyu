-- ============================================================
-- 修复：API Key（SK）value 列长度不足，导致创建接口必定失败
-- ============================================================
-- 现象（改造前）：
--   创建访问秘钥时后端已生成 sk-<压缩JWT> 并尝试落库，但 gmd_faq_secret_key.value 为 varchar(500)，
--   压缩后的 token 超过该长度 → DataIntegrityViolationException
--   → GlobalExceptionHandler 返回 400「字段长度超过限制」，且该行 value 落为 NULL，API Key 实际不可用。
--
-- 处理：
--   将 value 扩容为 varchar(2000)（压缩 JWT 典型长度 300~900，留足余量）。
--
-- 幂等性：
--   1) 表不存在（理论上不会）或列长度已 >= 2000 时不做任何修改；
--   2) 仅使用 MODIFY COLUMN，不改列名与其它列。
-- ============================================================

SET NAMES utf8mb4;

SET @tbl := 'gmd_faq_secret_key';
SET @table_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl);
SET @current_length := (SELECT IFNULL(CHARACTER_MAXIMUM_LENGTH, 0) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'value');

SET @sql := IF(@table_exists > 0 AND @current_length < 2000,
  CONCAT('ALTER TABLE `', @tbl, '` MODIFY COLUMN `value` varchar(2000) DEFAULT NULL COMMENT ''访问秘钥值：sk- 前缀 + 压缩JWT'''),
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
