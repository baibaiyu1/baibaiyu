-- 对话产物表：记录智能体在会话中实际产生/修改的工作空间文件。
--
-- 为什么需要独立表而不复用 gmd_faq_chat_message：
--   消息链是「父指针 + 会话游标」结构，且落库走 ConcurrentLogProducer 异步队列，
--   产物产生时拿不到稳定的 messageId。独立表存 session_id + create_time，
--   由读取方按时间序挂到对应消息上，避免依赖异步落库的时序。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，存量库与新库都安全。
CREATE TABLE IF NOT EXISTS `gmd_faq_chat_artifact` (
    `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`    bigint       NOT NULL COMMENT '租户ID',
    `session_id`   bigint       NOT NULL COMMENT '会话ID',
    `file_path`    varchar(512) NOT NULL COMMENT '工作空间内相对路径',
    `file_name`    varchar(255) NOT NULL COMMENT '文件名',
    `extension`    varchar(32)           DEFAULT NULL COMMENT '扩展名（不含点号，小写）',
    `size_bytes`   bigint                DEFAULT NULL COMMENT '文件大小（字节）',
    `source_tool`  varchar(64)           DEFAULT NULL COMMENT '产出该文件的工具名',
    `create_time`  datetime(3)  NOT NULL COMMENT '最近一次写入时间',
    PRIMARY KEY (`id`),
    -- 同一会话内一个路径只保留一行：文件被重新生成时做 upsert，避免卡片重复
    UNIQUE KEY `uk_session_path` (`session_id`, `file_path`),
    -- 按会话拉取产物列表
    KEY `idx_tenant_session_time` (`tenant_id`, `session_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话产物（智能体产生的文件）';
