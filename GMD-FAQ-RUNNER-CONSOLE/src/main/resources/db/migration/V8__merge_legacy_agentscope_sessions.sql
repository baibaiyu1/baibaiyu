-- ============================================================
-- AgentScope 会话表搬迁兜底：合并 V7 可能漏掉的存量会话
-- ============================================================
-- 背景（V7 的已知竞态）：
--   gmd_faq_agentscope_sessions 这张表由 AgentScope SDK 在
--   ApboaAgentSessionConfig 中按 TableConst.AGENT_SCOPE_SESSIONS 指向的表名
--   自动创建。runtime 与 console 并发启动时，SDK 可能抢在 V7 之前建出这张
--   空表，使 V7 的「旧表存在且新表不存在」条件不成立而跳过搬迁。
--   表现：旧表 agentscope_sessions 的会话数据静默滞留在旧表里，应用读不到。
--
-- 本脚本做什么：
--   1) 新旧表同时存在时，把旧表数据按主键 INSERT IGNORE 补齐到新表
--      （主键为 session_id + state_key + item_index，已迁移过的行自动跳过）；
--   2) 补齐后删除旧表，避免下次启动再被误判。
--
-- 幂等性：两条语句都只在「新旧表同时存在」时执行，且 INSERT IGNORE 可重复执行；
--   正常路径（V7 已成功搬迁、旧表已不存在）下本脚本为空操作。
--   注意：与 V7 同理，不能写死表名引用，必须用动态 SQL，否则旧表不存在时会直接报错。
-- ============================================================

-- ---------- 1. 判定新旧表是否存在 ----------
SET @has_old = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agentscope_sessions');
SET @has_new = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agentscope_sessions');

-- ---------- 2. 补齐数据：旧表有、新表也有时执行（列与 SDK 表结构一致） ----------
SET @merge_sql = IF(@has_old = 1 AND @has_new = 1,
  'INSERT IGNORE INTO `gmd_faq_agentscope_sessions`
     (`session_id`, `state_key`, `item_index`, `state_data`, `created_at`, `updated_at`)
   SELECT `session_id`, `state_key`, `item_index`, `state_data`, `created_at`, `updated_at`
     FROM `agentscope_sessions`',
  'SELECT 1');

PREPARE merge_stmt FROM @merge_sql;
EXECUTE merge_stmt;
DEALLOCATE PREPARE merge_stmt;

-- ---------- 3. 清理旧表：仅当新表存在（确认数据已有归属）时才删除 ----------
SET @drop_sql = IF(@has_old = 1 AND @has_new = 1,
  'DROP TABLE `agentscope_sessions`',
  'SELECT 1');

PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;
