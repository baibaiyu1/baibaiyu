-- ============================================================
-- GMD 命名迁移：项目自有表统一加 gmd_faq_ 前缀 + 存量数据改写
-- ============================================================
-- 背景：项目迁移为 GMD-FAQ-SERVER-MAIN 后，Java 包根由 com.hxh.apboa 改为
--       com.gmd.faq.server.main，库名由 apboa_next 改为 gmd_faq_server，
--       项目自有表统一加 gmd_faq_ 前缀。
--
-- 幂等设计（重要）：
--   本脚本同时面对两类环境，必须都安全：
--     1) 存量库：表名仍是旧名（account、chat_message …），需要搬迁到新表名；
--     2) 新库：sql/db_init.sql 已直接建为 gmd_faq_ 前缀表名，本脚本应为空操作。
--   因此不能直接写 RENAME TABLE / CREATE TABLE ... LIKE 旧表：
--     - 旧表不存在时 RENAME TABLE 与 CREATE TABLE ... LIKE 都会直接报错；
--     - 旧表不存在时 UPDATE 新表也会报错（新库需等建表后才可写）。
--   改为运行时读取 information_schema 组装动态 SQL：只对「旧表存在且新表不存在」
--   的表执行 RENAME，其余表跳过；两处存量数据改写放在搬迁之后，故对两类环境都安全。
--
--   注意：若某个环境里新旧表同时存在（例如旧代码期间应用已按新表名建过空表），
--   本脚本会跳过该表、旧表保留不删，不丢数据但需人工确认后再合并。
--
-- 作用范围：仅项目自有表。AgentScope SDK 落地表由本项目在 ApboaAgentSessionConfig
--   中显式传入表名（TableConst.AGENT_SCOPE_SESSIONS），属项目自有表，一并迁移。
--
-- 说明：归档分表（chat_message_yyyyMM）由 MessageTableRouter 运行时按需创建，
--   新分表会直接以 gmd_faq_ 前缀创建；存量分表按下方同名规则处理。
-- ============================================================

-- ---------- 1. 表名搬迁：旧名 → gmd_faq_ 前缀（幂等、按需） ----------
-- GROUP_CONCAT 默认上限 1024 字节，72 张表拼出的语句远超该值，必须先放开。
SET SESSION group_concat_max_len = 1024 * 1024;

SET @rename_sql = (
  SELECT IFNULL(
           CONCAT('RENAME TABLE ',
                  GROUP_CONCAT(CONCAT('`', o.TABLE_NAME, '` TO `gmd_faq_', o.TABLE_NAME, '`')
                               SEPARATOR ', ')),
           'SELECT 1')
  FROM information_schema.TABLES o
  LEFT JOIN information_schema.TABLES n
         ON n.TABLE_SCHEMA = o.TABLE_SCHEMA
        AND n.TABLE_NAME = CONCAT('gmd_faq_', o.TABLE_NAME)
  WHERE o.TABLE_SCHEMA = DATABASE()
    AND o.TABLE_NAME IN (
      'account', 'account_tenant', 'agent_a2a', 'agent_chat_key', 'agent_code_execution',
      'agent_definition', 'agent_hooks', 'agent_knowledge_bases', 'agent_long_term_memory',
      'agent_mcp_servers', 'agent_mcp_tool', 'agent_skill_packages', 'agent_studio',
      'agent_sub_agents', 'agent_tools', 'agent_workflows', 'agentscope_sessions',
      'attach', 'attach_chunk', 'attach_log', 'cache', 'channel', 'chat_message',
      'chat_session', 'chat_subagent_event', 'chat_subagent_run', 'code_execution_config',
      'dashboard', 'dashboard_dataset', 'dashboard_history', 'dashboard_user', 'datasource',
      'gateway_access_log', 'gateway_api', 'gateway_api_workflow', 'gateway_app',
      'hook_config', 'knowledge_base_config', 'long_term_memory_config', 'mcp_server',
      'mcp_tool', 'model_config', 'model_provider', 'mq', 'params', 'plugin',
      'quartz_job_info', 'quartz_job_log', 'quartz_job_records', 'rag_document',
      'rag_document_chunk', 'secret_key', 'sensitive_word_config', 'skill_file',
      'skill_package', 'skill_token', 'skill_tools', 'storage_protocol', 'studio_config',
      'system_prompt_template', 'tenant', 'tenant_join_request', 'tool_config',
      'workflow', 'workflow_cache', 'workflow_channel', 'workflow_datasource',
      'workflow_mq', 'workflow_node_execution', 'workflow_plugin', 'workflow_run',
      'workflow_version'
    )
    AND n.TABLE_NAME IS NULL
);

PREPARE rename_stmt FROM @rename_sql;
EXECUTE rename_stmt;
DEALLOCATE PREPARE rename_stmt;

-- ---------- 2. 存量数据改写：Quartz 任务类全限定名 ----------
-- job_class 以字符串持久化 Java 全限定类名，scheduler 的 JobInit 读取后反射加载。
-- 包根变更后必须改写存量值，否则历史定时任务 ClassNotFoundException 无法调度。
-- 必须在表名搬迁之后执行，且新库中不会命中任何行。
UPDATE gmd_faq_quartz_job_info
SET job_class = REPLACE(job_class, 'com.hxh.apboa', 'com.gmd.faq.server.main')
WHERE job_class LIKE 'com.hxh.apboa%';

-- ---------- 3. 存量数据改写：会话归档表名 ----------
-- chat_session.message_table 保存归档分表名（如 chat_message_202607），
-- MessageTableRouter 直接按该值路由并校验前缀，故存量值需加前缀。
-- 列宽 varchar(40)：gmd_faq_chat_message_yyyyMM = 27 字符，安全。
UPDATE gmd_faq_chat_session
SET message_table = CONCAT('gmd_faq_', message_table)
WHERE message_table LIKE 'chat\_message\_%';
