-- 后台异步子 Agent 结果自动回传（2026-11-03）
-- 1) 后台任务补投标记
-- 2) IM 会话出站目标持久化（唤醒轮/主动推送据此恢复目标）

ALTER TABLE ai_agent_background_task
    ADD COLUMN notified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '完成通知是否已随工作记忆持久化；0=待补投' AFTER ended_at_ms;

-- 升级前已结束的历史任务不再补投，避免一次性灌入全部旧通知。
UPDATE ai_agent_background_task
SET notified = 1
WHERE status <> 'running';

ALTER TABLE ai_agent_dialogue_session
    ADD COLUMN channel_chat_type VARCHAR(16) NULL COMMENT 'IM 出站会话形态：C2C/GROUP/GUILD/DM' AFTER binding_id,
    ADD COLUMN channel_target_id VARCHAR(128) NULL COMMENT 'IM 出站目标 ID（openid/group_openid/channel_id）' AFTER channel_chat_type;
