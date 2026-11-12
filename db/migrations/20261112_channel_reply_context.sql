-- 终答直推恢复 QQ 被动回复上下文（2026-11-12）

ALTER TABLE ai_agent_dialogue_session
    ADD COLUMN channel_last_msg_id VARCHAR(128) NULL COMMENT 'IM 最近入站消息 ID，用于被动回复' AFTER channel_target_id,
    ADD COLUMN channel_last_received_at DATETIME(3) NULL COMMENT 'IM 最近入站时刻（UTC），用于被动回复窗口' AFTER channel_last_msg_id;
