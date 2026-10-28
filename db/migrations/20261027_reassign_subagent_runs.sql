-- 20261027: 把记在父会话名下的子 Agent run 归位到子会话
-- 背景：
--   历史上子 Agent 的 run 有两种记账方式：
--     a) 记在 sub_ 子会话名下（34 条）—— 现行方式
--     b) 记在父会话名下（30 条）—— 旧方式，request_id 形如 sub:{agentId}:{suffix}
--   20261027_backfill_orphan_subagent_sessions.sql 补建了 214 个子会话身份行，
--   但统一填了 run_count=0 —— 对 b) 这类实际有轨迹的子会话是不准确的。
--   本脚本把 b) 的 run 迁到对应子会话名下，并重算会话计数。
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- ⚠️ 前置：ai_agent_dialogue_session_bak_orphan_20261027 必须存在。

-- ============================================================
-- 0) 备份 run 表
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_dialogue_run_bak_reassign_20261027` AS
SELECT * FROM `ai_agent_dialogue_run`;

-- ============================================================
-- 1) 预演：将迁移多少条 run、影响多少子会话
-- ============================================================
-- SELECT COUNT(*) AS runs_to_move,
--        COUNT(DISTINCT CONCAT('sub_', SUBSTRING_INDEX(SUBSTRING_INDEX(request_id,':',2),':',-1))) AS children
--   FROM ai_agent_dialogue_run
--  WHERE deleted=0 AND entry_agent='subagent' AND session_id NOT LIKE 'sub\_%';

-- ============================================================
-- 2) 归位：父会话名下的 subagent run → 子会话
--    只动 entry_agent='subagent' 且 request_id 能解析出 agentId 且目标子会话存在的行。
--    不动的：父会话自己的 react/plan_solve run（那是父的执行轨迹，不是子的）。
-- ============================================================
UPDATE `ai_agent_dialogue_run` r
SET r.`session_id` = CONCAT('sub_', SUBSTRING_INDEX(SUBSTRING_INDEX(r.`request_id`, ':', 2), ':', -1))
WHERE r.`deleted` = 0
  AND r.`entry_agent` = 'subagent'
  AND r.`session_id` NOT LIKE 'sub\_%'
  AND r.`request_id` LIKE 'sub:%'
  AND EXISTS (
      SELECT 1 FROM `ai_agent_dialogue_session` s
       WHERE s.`session_id` = CONCAT('sub_', SUBSTRING_INDEX(SUBSTRING_INDEX(r.`request_id`, ':', 2), ':', -1))
         AND s.`deleted` = 0
  );

-- ============================================================
-- 3) 重算受影响会话的计数（与 AgentExecutionRecorderImpl 规则一致）
--    finished = status=1 (SUCCESS)
--    failed   = status IN (2 FAILED, 3 TIMEOUT, 4 STOPPED)
--    started_at / last_active_at 取该会话 run 的最早开始 / 最晚结束
-- ============================================================
UPDATE `ai_agent_dialogue_session` c
JOIN (
    SELECT
        r.`session_id`,
        COUNT(*) AS runs,
        SUM(CASE WHEN r.`status` = 1 THEN 1 ELSE 0 END) AS finished,
        SUM(CASE WHEN r.`status` IN (2, 3, 4) THEN 1 ELSE 0 END) AS failed,
        MIN(r.`started_at`) AS first_start,
        MAX(COALESCE(r.`finished_at`, r.`started_at`)) AS last_end
      FROM `ai_agent_dialogue_run` r
     WHERE r.`deleted` = 0
       AND r.`session_id` LIKE 'sub\_%'
     GROUP BY r.`session_id`
) agg ON agg.`session_id` = c.`session_id`
SET c.`run_count`          = agg.`runs`,
    c.`finished_run_count` = agg.`finished`,
    c.`failed_run_count`   = agg.`failed`,
    c.`started_at`         = COALESCE(c.`started_at`, agg.`first_start`),
    c.`last_active_at`     = GREATEST(COALESCE(c.`last_active_at`, agg.`last_end`), agg.`last_end`)
WHERE c.`deleted` = 0
  AND c.`session_kind` = 'subagent';

-- ============================================================
-- 4) 验收
-- ============================================================
-- 4a) 不应再有 subagent run 挂在父会话名下
-- SELECT COUNT(*) AS left_on_parent FROM ai_agent_dialogue_run
--  WHERE deleted=0 AND entry_agent='subagent' AND session_id NOT LIKE 'sub\_%';
--
-- 4b) 计数不变量：finished 与 failed 都不超过 run_count
--     注意：不能校验 failed <= finished。两者在 AgentExecutionRecorderImpl 里是**独立增量**：
--       finished_delta = isSuccess(status) ? 1 : 0
--       failed_delta   = isFailed(status)  ? 1 : 0   （FAILED / TIMEOUT / STOPPED 都算）
--     所以一次 STOPPED 会让 failed 超过 finished。这是生产路径的既有行为，
--     全库 conversation 会话里也存在（2026-10-27 实测 59 个）。
--     DialogueSession.validateInvariants 里那条 failed <= finished 与写入路径矛盾，属既有缺陷。
-- SELECT COUNT(*) AS broken FROM ai_agent_dialogue_session
--  WHERE deleted=0 AND session_kind='subagent'
--    AND (finished_run_count > run_count OR failed_run_count > run_count);
--
-- 4c) 有 run 的子会话，其 run_count 必须与 run 表一致
-- SELECT COUNT(*) AS mismatch FROM (
--   SELECT s.session_id, s.run_count, COUNT(r.id) AS actual
--     FROM ai_agent_dialogue_session s
--     LEFT JOIN ai_agent_dialogue_run r ON r.session_id=s.session_id AND r.deleted=0
--    WHERE s.deleted=0 AND s.session_kind='subagent'
--    GROUP BY s.session_id, s.run_count
--   HAVING s.run_count <> COUNT(r.id)
-- ) d;

-- ============================================================
-- 5) 回滚
-- ============================================================
-- UPDATE ai_agent_dialogue_run r
--   JOIN ai_agent_dialogue_run_bak_reassign_20261027 b ON b.id = r.id
--    SET r.session_id = b.session_id
--  WHERE r.deleted=0;
