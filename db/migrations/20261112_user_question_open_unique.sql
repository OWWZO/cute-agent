-- 20261112: 同一会话只允许一条未删除、同状态的提问，给并发插入加数据库唯一约束。
--
-- 说明：唯一键包含 deleted 与 status，软删行不再占用活跃位置；历史状态不同
-- 的终态记录也不会占用 PENDING 的位置。清理重复行时给每行生成独立的历史
-- 状态，避免多条软删行都落在同一个 (status, deleted=1) 组合上。
--
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。执行前先备份目标表。

-- 0) 查重：每个 (session_id, status, deleted) 分组只能留 id 最大的一条。
SELECT session_id, status, deleted, COUNT(*) AS cnt, MAX(id) AS keep_id
  FROM ai_agent_user_question
 GROUP BY session_id, status, deleted
HAVING COUNT(*) > 1;

CREATE TABLE IF NOT EXISTS ai_agent_user_question_bak_open_20261112 AS
SELECT * FROM ai_agent_user_question;

-- 1) 清理重复：同一 (session_id, status, deleted) 只保留 id 最大的最新一条，
--    其余行软删；历史状态写入行 id，使 deleted=1 的墓碑行彼此不再重名。
-- 用备份表做关联，避免 MySQL 不允许 UPDATE 目标表出现在子查询中；H2 也能执行。
UPDATE ai_agent_user_question q
SET q.status = CONCAT('DELETED_', q.id),
    q.deleted = 1,
    q.update_time = NOW()
WHERE EXISTS (
    SELECT 1
      FROM ai_agent_user_question_bak_open_20261112 newer
     WHERE newer.session_id = q.session_id
       AND newer.status = q.status
       AND newer.deleted = q.deleted
       AND newer.id > q.id
);

-- 2) 复核：清理后不得再有任何唯一键冲突。
SELECT COUNT(*) AS duplicate_groups FROM (
  SELECT session_id, status, deleted
    FROM ai_agent_user_question
   GROUP BY session_id, status, deleted
  HAVING COUNT(*) > 1
) d;

-- 3) 唯一索引兜底并发插入；重复的第二个调用由 Java 侧转换为业务错误。
ALTER TABLE ai_agent_user_question
    ADD UNIQUE KEY uk_user_question_open (session_id, status, deleted);
