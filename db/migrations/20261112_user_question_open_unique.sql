-- 20261112: 同一会话同时只允许一条进行中的提问。
--
-- 本约束只覆盖 OPEN 状态（PENDING / RESUME_PENDING / RESUMING）且 deleted = 0；
-- ANSWERED / CANCELLED / TIMEOUT / FAILED 以及软删除记录不受限。
-- 本脚本不清理、不软删、不改写任何历史数据，只校验并创建唯一索引。
--
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。执行前先备份目标表。
-- 要求 MySQL 8.0.13+（表达式唯一索引）。
-- 回滚：DROP INDEX uk_user_question_open_only ON ai_agent_user_question;

-- 1) 执行前校验：结果必须为 0。若有冲突请停止执行，不要清理数据；
--    冲突存在时第 2 步的 CREATE UNIQUE INDEX 也会因重复键报错终止。
SELECT COUNT(*) AS duplicate_open_groups
  FROM (
    SELECT session_id
      FROM ai_agent_user_question
     WHERE deleted = 0
       AND status IN ('PENDING', 'RESUME_PENDING', 'RESUMING')
     GROUP BY session_id
    HAVING COUNT(*) > 1
  ) conflicting_sessions;

-- 2) 幂等创建表达式唯一索引。终态行和软删行的表达式结果均为 NULL，
--    唯一索引允许多个 NULL，因此不会参与互斥。
SET @user_question_open_only_index_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'ai_agent_user_question'
       AND index_name = 'uk_user_question_open_only'
);

SET @user_question_open_only_ddl = IF(
    @user_question_open_only_index_exists = 0,
    'CREATE UNIQUE INDEX uk_user_question_open_only ON ai_agent_user_question ((CASE WHEN deleted = 0 AND status IN (''PENDING'', ''RESUME_PENDING'', ''RESUMING'') THEN session_id ELSE NULL END))',
    'SELECT ''uk_user_question_open_only already exists'' AS migration_result'
);

PREPARE user_question_open_only_stmt FROM @user_question_open_only_ddl;
EXECUTE user_question_open_only_stmt;
DEALLOCATE PREPARE user_question_open_only_stmt;
