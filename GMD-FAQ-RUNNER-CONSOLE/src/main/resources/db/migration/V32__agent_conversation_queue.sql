-- 同会话串行占用与下一轮消息；运行租约过期后不重放已开始的消息。
CREATE TABLE IF NOT EXISTS gmd_faq_agent_run_lane (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    run_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    node_id VARCHAR(128) NULL,
    queue_id BIGINT NULL,
    lease_until DATETIME(3) NULL,
    PRIMARY KEY (tenant_id, user_id, session_id),
    KEY idx_lane_dispatch (state, paused, lease_until)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS gmd_faq_agent_queued_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content TEXT NOT NULL,
    options JSON NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    run_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_queue_request (tenant_id, user_id, session_id, request_id),
    KEY idx_queue_session (tenant_id, user_id, session_id, state, id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
