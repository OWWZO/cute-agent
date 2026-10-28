-- 20261027: 补建孤儿子会话身份行
-- 背景：
--   20261027_working_memory_subagent_key_unify.sql 把子 Agent 记忆从
--   (父sessionId, 'sub:{agentId}') 迁到了 (childSessionId, 'main')。
--   其中 244 个组合只有 30 个能找到对应的 session_kind='subagent' 会话行，
--   另外 214 个的父会话存在、子会话行从未创建（早期版本只写了记忆、没写会话身份）。
--   迁移后这些记忆归在 (sub_xxx, 'main') 下但不可达——本脚本补建身份行让它们可访问。
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- ⚠️ 前置：ai_agent_working_memory_turn_bak_20261027 必须存在（父会话映射只在它里面，
--    正式表的 memory_scope 已被改写成 'main'）。
-- ⚠️ 执行前备份 ai_agent_dialogue_session。

-- ============================================================
-- 0) 备份
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_dialogue_session_bak_orphan_20261027` AS
SELECT * FROM `ai_agent_dialogue_session`;

-- ============================================================
-- 1) 预演：将补建多少行
-- ============================================================
-- SELECT COUNT(*) FROM (
--   SELECT DISTINCT b.session_id AS parent_id, CONCAT('sub_', SUBSTRING(b.memory_scope,5)) AS child_id
--   FROM ai_agent_working_memory_turn_bak_20261027 b
--   WHERE b.memory_scope LIKE 'sub:%' AND b.deleted = 0
-- ) m
-- LEFT JOIN ai_agent_dialogue_session s ON s.session_id = m.child_id AND s.deleted = 0
-- WHERE s.session_id IS NULL;

-- ============================================================
-- 2) 补建身份行
--    字段语义：
--      session_kind      = 'subagent'（与既有子会话一致）
--      parent_session_id = 备份里的父会话
--      root_session_id   = 父的 root_session_id（父已是根时等于父自己）
--      user_id           = 父的 user_id（记忆是父派发时产生的）
--      binding_id        = 父的 binding_id（血缘继承）
--      title             = '子智能体任务'（与 SubAgentSessionPortAdapter.DEFAULT_TITLE 一致）
--      status            = 1（SUCCESS）：这些都是历史记忆，没有进行中的 run
--      run_count         = 0
--                         刻意不伪造：这些子会话从未在 ai_agent_dialogue_run 里留下 run，
--                         填 1 会让侧栏显示「1 轮」却点不出任何内容
--      started_at / last_active_at = 该子会话记忆的最早/最晚时间
-- ============================================================
INSERT INTO `ai_agent_dialogue_session` (
    `session_id`, `user_id`, `binding_id`, `session_kind`, `parent_session_id`, `root_session_id`,
    `title`, `status`, `run_count`, `finished_run_count`, `failed_run_count`,
    `started_at`, `last_active_at`, `create_time`, `update_time`, `deleted`
)
SELECT
    m.`child_id`,
    p.`user_id`,
    p.`binding_id`,
    'subagent',
    m.`parent_id`,
    COALESCE(p.`root_session_id`, p.`session_id`),
    '子智能体任务',
    1,
    0, 0, 0,
    w.`first_mem`,
    w.`last_mem`,
    NOW(), NOW(), 0
FROM (
    SELECT DISTINCT b.`session_id` AS parent_id,
           CONCAT('sub_', SUBSTRING(b.`memory_scope`, 5)) AS child_id
      FROM `ai_agent_working_memory_turn_bak_20261027` b
     WHERE b.`memory_scope` LIKE 'sub:%' AND b.`deleted` = 0
) m
INNER JOIN `ai_agent_dialogue_session` p
        ON p.`session_id` = m.`parent_id` AND p.`deleted` = 0
LEFT JOIN `ai_agent_dialogue_session` s
       ON s.`session_id` = m.`child_id` AND s.`deleted` = 0
LEFT JOIN (
    SELECT `session_id`, MIN(`create_time`) AS first_mem, MAX(`create_time`) AS last_mem
      FROM `ai_agent_working_memory_turn`
     WHERE `deleted` = 0 AND `memory_scope` = 'main' AND `session_id` LIKE 'sub\_%'
     GROUP BY `session_id`
) w ON w.`session_id` = m.`child_id`
WHERE s.`session_id` IS NULL;

-- ============================================================
-- 3) 验收
-- ============================================================
-- 3a) 不应再有「有记忆但无会话行」的子会话
-- SELECT COUNT(*) AS still_orphan FROM (
--   SELECT DISTINCT w.session_id
--     FROM ai_agent_working_memory_turn w
--    WHERE w.deleted=0 AND w.memory_scope='main' AND w.session_id LIKE 'sub\_%'
-- ) x
-- LEFT JOIN ai_agent_dialogue_session s ON s.session_id=x.session_id AND s.deleted=0
-- WHERE s.session_id IS NULL;
--
-- 3b) 每个子会话的 root 必须能对上父的 root
-- SELECT COUNT(*) AS bad_root FROM ai_agent_dialogue_session c
--   JOIN ai_agent_dialogue_session p ON p.session_id=c.parent_session_id AND p.deleted=0
--  WHERE c.deleted=0 AND c.session_kind='subagent'
--    AND c.root_session_id <> COALESCE(p.root_session_id, p.session_id);
--
-- 3c) 会话类型分布
-- SELECT session_kind, COUNT(*) FROM ai_agent_dialogue_session WHERE deleted=0 GROUP BY session_kind;

-- ============================================================
-- 4) 回滚
-- ============================================================
-- DELETE FROM ai_agent_dialogue_session
--  WHERE deleted=0 AND session_kind='subagent'
--    AND session_id NOT IN (SELECT session_id FROM ai_agent_dialogue_session_bak_orphan_20261027);
