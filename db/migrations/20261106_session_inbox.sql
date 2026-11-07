-- 用户中途输入与后台任务通知统一投递队列。
-- 数据库是唯一事实源；内存队列只作为唤醒提示。
CREATE TABLE IF NOT EXISTS ai_agent_session_inbox (
    id                 BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID（同时作为会话内投递顺序）',
    session_id         VARCHAR(128)   NOT NULL COMMENT '会话ID',
    kind               VARCHAR(32)    NOT NULL COMMENT 'USER_MESSAGE|BACKGROUND_NOTIFICATION',
    visibility         VARCHAR(16)    NOT NULL COMMENT 'ALL|MODEL_ONLY',
    state              VARCHAR(16)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|CLAIMED|DELIVERED|HELD',
    idempotency_key    VARCHAR(160)   NOT NULL COMMENT '幂等键：用户 clientMessageId / background taskId',
    payload_json       MEDIUMTEXT     NOT NULL COMMENT '原始消息载荷 JSON',
    source_run_id      VARCHAR(64)    NULL COMMENT '产生该消息的 run / requestId',
    claimed_by_run_id  VARCHAR(64)    NULL COMMENT '当前认领该消息的 run',
    lease_until_ms     BIGINT         NULL COMMENT '认领租约到期 epoch ms',
    created_at_ms      BIGINT         NOT NULL COMMENT '创建时间 epoch ms',
    delivered_at_ms    BIGINT         NULL COMMENT '写入工作记忆并 ACK 的时间 epoch ms',
    create_time        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted            TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '软删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_inbox_key (session_id, idempotency_key, deleted),
    KEY idx_session_inbox_claim (session_id, kind, state, id, deleted),
    KEY idx_session_inbox_state (session_id, state, id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话级用户输入/后台通知统一投递队列';
