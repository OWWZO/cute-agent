-- 20261027: 会话表加 sub_agent_type，让子会话的工具池由定义表决定
-- 背景：
--   子 Agent 的工具池此前由「谁派发的」决定：模式 1（父派发）在 SubAgentRunner 里用
--   SubAgentDefinition 过滤；模式 2（用户直聊子会话）没有父上下文，拿不到定义，
--   只能给全量工具池。两个入口因此不一致。
--   本列把「这个会话是什么 Agent」记在会话身份上，两个入口都从它查定义表，
--   工具池收敛为「由会话身份决定」。
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- ⚠️ 前置：ai_agent_dialogue_session_bak_orphan_20261027 已存在（上一轮迁移的备份）。

-- ============================================================
-- 0) 备份
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_dialogue_session_bak_subagenttype_20261027` AS
SELECT * FROM `ai_agent_dialogue_session`;

-- ============================================================
-- 1) 加列（身份列，只在建行时写入，ON DUPLICATE 段不覆盖）
-- ============================================================
ALTER TABLE `ai_agent_dialogue_session`
    ADD COLUMN `sub_agent_type` VARCHAR(64) NULL
        COMMENT '子智能体类型（对应 ai_agent_sub_agent_definition.agent_key）；决定该会话的工具池。NULL=未记录，回退全量工具'
        AFTER `root_session_id`;

-- ============================================================
-- 2) 回填：只能从工具调用账本反推
--    历史子会话行里没有记录类型，唯一痕迹是 ai_agent_tool_invocation.sub_agent_type
--    （子 Agent 的每次工具调用都带着它）。该表没有 session_id，经 run_id 关联到会话。
--    同一子会话下取值唯一时才回填，有歧义（多类型混用）的留 NULL。
--    2026-10-27 实测：53 可回填 / 0 歧义 / 194 无痕迹。
-- ============================================================
-- 2a) 预演：能回填多少、留空多少
-- SELECT
--     SUM(CASE WHEN t.types = 1 THEN 1 ELSE 0 END) AS backfillable,
--     SUM(CASE WHEN t.types > 1 THEN 1 ELSE 0 END) AS ambiguous,
--     SUM(CASE WHEN t.types IS NULL THEN 1 ELSE 0 END) AS no_trace
--   FROM ai_agent_dialogue_session s
--   LEFT JOIN (
--       SELECT r.session_id, COUNT(DISTINCT ti.sub_agent_type) AS types
--         FROM ai_agent_tool_invocation ti
--         JOIN ai_agent_dialogue_run r ON r.id = ti.run_id AND r.deleted = 0
--        WHERE ti.deleted = 0 AND ti.sub_agent_type IS NOT NULL AND ti.sub_agent_type <> ''
--        GROUP BY r.session_id
--   ) t ON t.session_id = s.session_id
--  WHERE s.deleted = 0 AND s.session_kind = 'subagent';

UPDATE `ai_agent_dialogue_session` s
JOIN (
    SELECT r.`session_id`, MIN(ti.`sub_agent_type`) AS agent_type
      FROM `ai_agent_tool_invocation` ti
      JOIN `ai_agent_dialogue_run` r ON r.`id` = ti.`run_id` AND r.`deleted` = 0
     WHERE ti.`deleted` = 0
       AND ti.`sub_agent_type` IS NOT NULL
       AND ti.`sub_agent_type` <> ''
     GROUP BY r.`session_id`
    HAVING COUNT(DISTINCT ti.`sub_agent_type`) = 1
) t ON t.`session_id` = s.`session_id`
SET s.`sub_agent_type` = t.`agent_type`
WHERE s.`deleted` = 0
  AND s.`session_kind` = 'subagent'
  AND s.`sub_agent_type` IS NULL;

-- ============================================================
-- 3) 验收
-- ============================================================
-- 3a) 回填结果分布（NULL 是预期结果：无类型痕迹的老会话回退全量工具池）
-- SELECT IFNULL(sub_agent_type,'(null)') AS agent_type, COUNT(*) AS sessions
--   FROM ai_agent_dialogue_session
--  WHERE deleted = 0 AND session_kind = 'subagent'
--  GROUP BY sub_agent_type ORDER BY sessions DESC;
--
-- 3b) 回填的类型必须都能解析到定义（否则运行时回退全量，等于没生效）
--     'general-purpose' 是 SubAgentRegistry 的内置类型，不在定义表里，属正常。
-- SELECT s.sub_agent_type, COUNT(*) AS orphan_sessions
--   FROM ai_agent_dialogue_session s
--   LEFT JOIN ai_agent_sub_agent_definition d
--          ON d.agent_key = s.sub_agent_type AND d.deleted = 0 AND d.status = 1
--  WHERE s.deleted = 0 AND s.sub_agent_type IS NOT NULL
--    AND s.sub_agent_type <> 'general-purpose'
--    AND d.agent_key IS NULL
--  GROUP BY s.sub_agent_type;
--
-- 3c) 主会话不得带类型
-- SELECT COUNT(*) AS bad_main FROM ai_agent_dialogue_session
--  WHERE deleted = 0 AND session_kind = 'conversation' AND sub_agent_type IS NOT NULL;

-- ============================================================
-- 4) 回滚
-- ============================================================
-- UPDATE ai_agent_dialogue_session s
--   JOIN ai_agent_dialogue_session_bak_subagenttype_20261027 b ON b.session_id = s.session_id
--    SET s.sub_agent_type = b.sub_agent_type
--  WHERE s.deleted = 0;
-- ALTER TABLE `ai_agent_dialogue_session` DROP COLUMN `sub_agent_type`;
