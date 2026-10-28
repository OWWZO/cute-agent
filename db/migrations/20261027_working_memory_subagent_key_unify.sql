-- 20261027: 子会话工作记忆键统一 (session_id, 'main')
-- 用途：
--   子会话不再是「挂在父会话 sub:{agentId} 作用域下的侧链」，而是与主会话同构的独立会话：
--   记忆按 (childSessionId, 'main') 存。父派发子 Agent 与用户从会话树点进子会话续聊，
--   读写的是同一份记忆——这正是「同一个会话的两种驱动方式」。
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- ⚠️ 执行前必须备份 ai_agent_working_memory_turn。
-- ⚠️ 执行期间不要有子 Agent 在跑：迁移与在线写入会竞争同一个唯一键。
--
-- 不变量：
--   uk_wm_turn_request (request_id, deleted)              —— request_id 不变，不撞
--   uk_wm_turn_session_scope_seq (session_id, memory_scope, turn_seq, deleted)
--                                                        —— 靠 turn_seq 偏移避开
--   ai_agent_working_memory_message.turn_id 是 FK 到 turn.id，改 turn.session_id 不影响 message 行

-- ============================================================
-- 0) 备份
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_working_memory_turn_bak_20261027` AS
SELECT * FROM `ai_agent_working_memory_turn`;

-- ============================================================
-- 1) 预演：看看要迁移多少行、涉及多少个子会话
-- ============================================================
-- SELECT COUNT(*) AS rows_to_migrate FROM ai_agent_working_memory_turn WHERE memory_scope LIKE 'sub:%';
-- SELECT session_id, memory_scope, COUNT(*) AS cnt
--   FROM ai_agent_working_memory_turn
--  WHERE memory_scope LIKE 'sub:%'
--  GROUP BY session_id, memory_scope;

-- ============================================================
-- 2) 逐 (父sessionId, sub:agentId) 对迁移
--    childSessionId = CONCAT('sub_', SUBSTRING(memory_scope, 5))
--    turn_seq 偏移到目标键现有最大值之后，避免撞 uk_wm_turn_session_scope_seq
-- ============================================================
UPDATE `ai_agent_working_memory_turn` t
INNER JOIN (
    SELECT
        t2.`session_id` AS src_session_id,
        t2.`memory_scope` AS src_scope,
        CONCAT('sub_', SUBSTRING(t2.`memory_scope`, 5)) AS target_session_id,
        COALESCE((
            SELECT MAX(t3.`turn_seq`)
              FROM `ai_agent_working_memory_turn` t3
             WHERE t3.`session_id` = CONCAT('sub_', SUBSTRING(t2.`memory_scope`, 5))
               AND t3.`memory_scope` = 'main'
               AND t3.`deleted` = 0
        ), 0) AS seq_offset
      FROM `ai_agent_working_memory_turn` t2
     WHERE t2.`memory_scope` LIKE 'sub:%'
       AND t2.`deleted` = 0
     GROUP BY t2.`session_id`, t2.`memory_scope`
) m ON t.`session_id` = m.src_session_id AND t.`memory_scope` = m.src_scope
SET t.`session_id`   = m.target_session_id,
    t.`memory_scope` = 'main',
    t.`turn_seq`     = t.`turn_seq` + m.seq_offset
WHERE t.`deleted` = 0;

-- ============================================================
-- 3) 验收：残留必须为 0
-- ============================================================
-- SELECT COUNT(*) FROM ai_agent_working_memory_turn WHERE memory_scope LIKE 'sub:%';
-- 迁移后 (childSessionId, main) 的 turn_seq 连续无重复：
-- SELECT session_id, COUNT(*) AS cnt, COUNT(DISTINCT turn_seq) AS distinct_cnt, MAX(turn_seq) AS max_seq
--   FROM ai_agent_working_memory_turn
--  WHERE memory_scope = 'main' AND deleted = 0 AND session_id LIKE 'sub\_%'
--  GROUP BY session_id
-- HAVING cnt <> distinct_cnt;

-- ============================================================
-- 4) 回滚（如需）
-- ============================================================
-- TRUNCATE TABLE `ai_agent_working_memory_turn`;
-- INSERT INTO `ai_agent_working_memory_turn` SELECT * FROM `ai_agent_working_memory_turn_bak_20261027`;
