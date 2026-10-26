-- Agent publication and external guest access are independent of internal visibility.
-- Preserve the behavior of existing enabled agents while new agents start unpublished.
SET @agent_published_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition'
      AND COLUMN_NAME = 'published'
);
SET @ddl := IF(@agent_published_exists = 0,
    'ALTER TABLE `gmd_faq_agent_definition` ADD COLUMN `published` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''Whether the agent has been published''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := IF(@agent_published_exists = 0,
    'UPDATE `gmd_faq_agent_definition` SET `published` = CASE WHEN `enabled` = 1 THEN 1 ELSE 0 END',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_agent_definition'
                  AND COLUMN_NAME = 'external_share_enabled') = 0,
    'ALTER TABLE `gmd_faq_agent_definition` ADD COLUMN `external_share_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''Allow guest access through a share link''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Snapshot the session department. Moving an account must not transfer old
-- conversations and files into another department leader's scope.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_chat_session'
                  AND COLUMN_NAME = 'owner_dept_id') = 0,
    'ALTER TABLE `gmd_faq_chat_session` ADD COLUMN `owner_dept_id` bigint DEFAULT NULL COMMENT ''Department when the session was created''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `gmd_faq_chat_session` s
JOIN `gmd_faq_account` a ON a.`id` = s.`user_id`
JOIN `gmd_faq_department` d ON d.`id` = a.`dept_id` AND d.`tenant_id` = s.`tenant_id`
SET s.`owner_dept_id` = a.`dept_id`
WHERE s.`owner_dept_id` IS NULL AND a.`dept_id` IS NOT NULL;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gmd_faq_chat_session'
                  AND INDEX_NAME = 'idx_chat_session_owner_dept') = 0,
    'ALTER TABLE `gmd_faq_chat_session` ADD KEY `idx_chat_session_owner_dept` (`tenant_id`, `owner_dept_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Only administrators may enable the external guest channel. The owner can
-- rotate its link after the channel has been approved and enabled.
INSERT IGNORE INTO `gmd_faq_menu`
    (`id`, `parent_id`, `name`, `menu_type`, `perms`, `sort_order`, `status`)
VALUES (3113, 3100, '管理智能体游客访问', 'BUTTON', 'agent:external:manage', 13, 1);

INSERT IGNORE INTO `gmd_faq_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`)
SELECT (r.`id` + m.`id`) & 0x3FFFFFFFFFFFFFFF, r.`id`, m.`id`, r.`tenant_id`
FROM `gmd_faq_role` r
CROSS JOIN `gmd_faq_menu` m
WHERE r.`builtin` = 1 AND r.`code` IN ('superadmin', 'system_admin') AND m.`id` = 3113;

DELETE FROM `gmd_faq_role_menu`
WHERE `menu_id` = 3113
  AND `role_id` IN (
      SELECT `id` FROM (
          SELECT `id` FROM `gmd_faq_role`
          WHERE `builtin` = 1 AND `code` IN ('editor', 'viewer')
      ) AS business_roles
  );
