-- 20261027: 会话级偏好 + 会话树根（specs: 子会话作为一等公民）
-- 用途：
--   1) ai_agent_dialogue_session 加 model / thinking / thinking_effort / deep_think
--      —— 子会话在父 Agent 结束、内存上下文被回收后，仍能从库里重建自己的运行时选择。
--   2) ai_agent_dialogue_session 加 root_session_id
--      —— parent_session_id 是权威血缘链，root_session_id 是它的派生缓存，
--         用于一次查询取整棵会话树（将来开放多层嵌套时不必再改 schema）。
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- ⚠️ 仓库内 Cute-agent-app/src/main/resources/db/schema.sql 只是**滞后快照**，
--    执行前请先在线上库确认这些列尚不存在（information_schema.COLUMNS）。

-- ============================================================
-- 1) 会话级偏好四列
-- ============================================================
-- deep_think 为什么 NOT NULL DEFAULT 0：它是布尔开关，前端永远传 1/0，没有「未选择」的歧义。
-- 其余三列允许 NULL，语义是「用户从未显式选择过」，读取方据此回退到默认。
ALTER TABLE `ai_agent_dialogue_session`
    ADD COLUMN `model`           VARCHAR(128) NULL COMMENT '会话级模型标识；NULL=未显式选择' AFTER `last_active_at`,
    ADD COLUMN `thinking`        TINYINT(1)   NULL COMMENT '是否开启深度思考；NULL=未显式选择' AFTER `model`,
    ADD COLUMN `thinking_effort` VARCHAR(32)  NULL COMMENT '思考强度（low/medium/high）；NULL=默认' AFTER `thinking`,
    ADD COLUMN `deep_think`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Deep Think 开关；派生 agentType' AFTER `thinking_effort`;

-- ============================================================
-- 2) 会话树根
-- ============================================================
ALTER TABLE `ai_agent_dialogue_session`
    ADD COLUMN `root_session_id` VARCHAR(128) NULL
        COMMENT '根会话ID；根会话指向自己，子会话指向树根。用于一次查询取整棵会话树'
        AFTER `parent_session_id`,
    ADD KEY `idx_dialogue_session_root` (`root_session_id`, `deleted`);

-- 存量回填。执行顺序不能反：先根后子，子会话才能读到已回填的父 root。
-- 1) 根会话（无父）指向自己
UPDATE `ai_agent_dialogue_session`
SET `root_session_id` = `session_id`
WHERE `parent_session_id` IS NULL
  AND `root_session_id` IS NULL;

-- 2) 一层子会话：root = 父的 root（父尚未回填时退化为「父即根」）
UPDATE `ai_agent_dialogue_session` c
INNER JOIN `ai_agent_dialogue_session` p ON p.`session_id` = c.`parent_session_id` AND p.`deleted` = 0
SET c.`root_session_id` = COALESCE(p.`root_session_id`, p.`session_id`)
WHERE c.`parent_session_id` IS NOT NULL
  AND c.`root_session_id` IS NULL;

-- 当前只有两层，跑一次即可。若线上库存在更深嵌套，重复第 2 条直到 affected rows = 0。
