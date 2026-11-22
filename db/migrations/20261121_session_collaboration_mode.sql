-- 20261121: Deep Think 迁移为主协作模式 + 会话提示词快照
--
-- 旧语义：
--   deep_think = 0 -> ReAct
--   deep_think = 1 -> PlanSolve
--
-- 新语义：
--   collaboration_mode = normal  -> 普通模式，主 Agent 为 main_agent_key
--   collaboration_mode = cluster -> 集群模式，主 Agent 为通用协调者
--
-- 项目没有 Flyway/Liquibase，本文件由人工执行。

-- 0) 备份
CREATE TABLE IF NOT EXISTS `ai_agent_dialogue_session_bak_collabmode_20261121` AS
SELECT * FROM `ai_agent_dialogue_session`;

-- 1) 字段改名并转换类型。旧 0/1 先保留为字符串，随后显式回填。
ALTER TABLE `ai_agent_dialogue_session`
    CHANGE COLUMN `deep_think` `collaboration_mode` VARCHAR(16) NOT NULL DEFAULT 'normal'
        COMMENT '主模式：normal=普通 Agent；cluster=通用协调者集群';

UPDATE `ai_agent_dialogue_session`
SET `collaboration_mode` = CASE `collaboration_mode`
    WHEN '1' THEN 'cluster'
    WHEN '0' THEN 'normal'
    ELSE 'normal'
END;

-- 2) 会话身份与稳定提示词快照
ALTER TABLE `ai_agent_dialogue_session`
    ADD COLUMN `main_agent_key` VARCHAR(64) NULL
        COMMENT '普通模式主 Agent key；cluster 模式为空' AFTER `sub_agent_type`,
    ADD COLUMN `prompt_version` VARCHAR(64) NULL
        COMMENT '会话稳定提示词版本' AFTER `collaboration_mode`,
    ADD COLUMN `prompt_hash` CHAR(64) NULL
        COMMENT '稳定提示词 SHA-256' AFTER `prompt_version`,
    ADD COLUMN `prompt_snapshot` MEDIUMTEXT NULL
        COMMENT '会话冻结的 system 提示词快照；内部字段' AFTER `prompt_hash`;

-- 3) 验收
SELECT collaboration_mode, COUNT(*) AS sessions
FROM ai_agent_dialogue_session
WHERE deleted = 0
GROUP BY collaboration_mode;

SELECT COUNT(*) AS invalid_mode
FROM ai_agent_dialogue_session
WHERE deleted = 0
  AND collaboration_mode NOT IN ('normal', 'cluster');

SELECT COUNT(*) AS bad_main_agent
FROM ai_agent_dialogue_session
WHERE deleted = 0
  AND session_kind = 'conversation'
  AND collaboration_mode = 'cluster'
  AND main_agent_key IS NOT NULL;
