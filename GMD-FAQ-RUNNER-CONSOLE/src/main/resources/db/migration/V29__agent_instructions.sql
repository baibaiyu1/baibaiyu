-- 活动运行补充指令，与会话历史分开；不改已发布状态布局。
CREATE TABLE IF NOT EXISTS gmd_faq_agent_instruction_run (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    run_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, session_id, run_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 独立操作权限，默认只给内置管理员/编辑角色；自定义角色可在角色授权页面勾选。
INSERT IGNORE INTO gmd_faq_menu (id,parent_id,name,menu_type,perms,sort_order,status)
VALUES (3114,3100,'运行中补充要求','BUTTON','agent:chat:instruct',14,1);
INSERT IGNORE INTO gmd_faq_role_menu (id,role_id,menu_id,tenant_id)
SELECT (r.id + 3114) & 0x3FFFFFFFFFFFFFFF, r.id, 3114, r.tenant_id
FROM gmd_faq_role r
WHERE r.builtin=1 AND r.code IN ('superadmin','system_admin','editor')
  AND r.id <= 9223372036854775807 - 3114;

CREATE TABLE IF NOT EXISTS gmd_faq_agent_instruction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    run_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(4000) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_instruction_request (tenant_id, user_id, session_id, run_id, request_id),
    KEY idx_instruction_session (tenant_id, user_id, session_id, id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
