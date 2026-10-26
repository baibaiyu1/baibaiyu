-- AgentScope 2.0 的状态存储使用 version 做乐观锁；保留已有 1.x 状态和主键。
CREATE TABLE IF NOT EXISTS `gmd_faq_agentscope_sessions` (
    `session_id` VARCHAR(255) NOT NULL,
    `state_key` VARCHAR(255) NOT NULL,
    `item_index` INT NOT NULL DEFAULT 0,
    `state_data` LONGTEXT NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `version` BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (`session_id`, `state_key`, `item_index`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET @has_state_version = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_agentscope_sessions'
        AND column_name = 'version'
);
SET @state_version_sql = IF(@has_state_version = 0,
    'ALTER TABLE `gmd_faq_agentscope_sessions` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 1',
    'SELECT 1');
PREPARE state_version_stmt FROM @state_version_sql;
EXECUTE state_version_stmt;
DEALLOCATE PREPARE state_version_stmt;
