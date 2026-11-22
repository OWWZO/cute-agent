-- 多层级子 Agent 树（2026-11-20）
-- 逻辑任务继续复用 ai_agent_background_task，不新增 transcript / logical-run 表。

ALTER TABLE ai_agent_background_task
    ADD COLUMN child_session_id VARCHAR(128) NULL COMMENT '子 Agent 子会话 ID' AFTER agent_type,
    ADD COLUMN parent_session_id VARCHAR(128) NULL COMMENT '直接父会话 ID' AFTER child_session_id,
    ADD COLUMN parent_request_id VARCHAR(128) NULL COMMENT '发起派发的父 run requestId' AFTER parent_session_id,
    ADD COLUMN parent_tool_use_id VARCHAR(128) NULL COMMENT '父 Agent/SendMessage 工具调用 ID' AFTER parent_request_id,
    ADD COLUMN parent_agent_id VARCHAR(64) NULL COMMENT '直接父 Agent 稳定 ID' AFTER parent_tool_use_id,
    ADD COLUMN owner_agent_id VARCHAR(64) NULL COMMENT '创建该后台任务的当前 Agent ID' AFTER parent_agent_id,
    ADD COLUMN root_session_id VARCHAR(128) NULL COMMENT '整棵子 Agent 树的根会话 ID' AFTER owner_agent_id,
    ADD COLUMN delegation_depth INT NULL COMMENT '根 Agent=0，子 Agent=1' AFTER root_session_id,
    ADD COLUMN phase VARCHAR(32) NULL COMMENT 'running|waiting_background' AFTER delegation_depth,
    ADD COLUMN execution_mode VARCHAR(16) NULL COMMENT 'inline|pool' AFTER phase,
    ADD COLUMN closing_turn_count INT NOT NULL DEFAULT 0 COMMENT '后台唤醒后的收尾轮次数' AFTER execution_mode;

-- 旧行只做可解释的最小回填：无法从历史数据还原更深的真实父子关系。
UPDATE ai_agent_background_task
SET child_session_id = CONCAT('sub_', agent_id)
WHERE child_session_id IS NULL
  AND agent_id IS NOT NULL;

UPDATE ai_agent_background_task
SET parent_session_id = session_id,
    parent_agent_id = session_id,
    owner_agent_id = session_id,
    root_session_id = session_id,
    delegation_depth = 1,
    execution_mode = COALESCE(execution_mode, 'pool')
WHERE type = 'local_agent'
  AND root_session_id IS NULL;

UPDATE ai_agent_background_task
SET phase = CASE WHEN status = 'running' THEN 'running' ELSE NULL END
WHERE phase IS NULL;

CREATE INDEX idx_bg_tree_root_status_agent
    ON ai_agent_background_task (root_session_id, status, agent_id);
CREATE INDEX idx_bg_tree_parent_agent
    ON ai_agent_background_task (parent_session_id, agent_id);
CREATE INDEX idx_bg_tree_owner_status
    ON ai_agent_background_task (owner_agent_id, status);
CREATE INDEX idx_bg_tree_child_status
    ON ai_agent_background_task (child_session_id, status);
