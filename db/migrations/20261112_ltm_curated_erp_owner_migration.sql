-- 20261112: 把旧 ERP 兜底写出的 LTM owner 一次性改成 ERP 语义。
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- 判据：user_account.user_id 是稳定 userId。历史 USER 行的 owner_id 不是账号 ID，
-- 但其 source_session_id 对应的有效会话 user_id 是账号 ID 时，owner_id 才是旧
-- LtmOwnerResolver 的 ERP 兜底值；ai_agent_dialogue_run 对可精确关联写入请求的
-- 行进一步证明 run 身份与会话身份一致，而不等于旧 owner_id。
-- 迁移只改 owner_type；owner_id 保留裸 id，与 MyBatisCuratedMemoryStore 的
-- selectActive(ownerType, ownerId, ...) 查询契约一致。

-- ============================================================
-- 0) 执行前备份；备份表保留旧 owner_type，供反向 UPDATE / 精确恢复。
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_agent_ltm_curated_entry_bak_ltm_20261112 AS
SELECT * FROM ai_agent_ltm_curated_entry;

-- ============================================================
-- 1) 预演：受影响行数（含 deleted 行，避免留下旧语义的审计数据）。
-- ============================================================
SELECT COUNT(*) AS affected_rows
  FROM ai_agent_ltm_curated_entry c
 WHERE c.owner_type = 'USER'
   AND NOT EXISTS (SELECT 1 FROM user_account owner_account
                    WHERE owner_account.user_id = c.owner_id AND owner_account.deleted = 0)
   AND EXISTS (
       SELECT 1 FROM ai_agent_dialogue_session s
        WHERE s.session_id = c.source_session_id
          AND s.deleted = 0
          AND s.user_id IS NOT NULL
          AND s.user_id <> c.owner_id
          AND EXISTS (SELECT 1 FROM user_account session_account
                       WHERE session_account.user_id = s.user_id AND session_account.deleted = 0)
          AND NOT EXISTS (
              SELECT 1 FROM ai_agent_dialogue_run r
               WHERE r.session_id = c.source_session_id
                 AND r.request_id = c.source_request_id
                 AND r.user_id IS NOT NULL
                 AND r.user_id <> s.user_id
          )
   );

-- 复核：真实 userId 行即使与会话身份不一致，也不得进入迁移集合。
SELECT COUNT(*) AS protected_real_user_rows
  FROM ai_agent_ltm_curated_entry c
  JOIN ai_agent_dialogue_session s
    ON s.session_id = c.source_session_id
   AND s.deleted = 0
   AND s.user_id IS NOT NULL
   AND s.user_id <> c.owner_id
 WHERE c.owner_type = 'USER'
   AND EXISTS (SELECT 1 FROM user_account owner_account
                WHERE owner_account.user_id = c.owner_id AND owner_account.deleted = 0)
   AND NOT EXISTS (
       SELECT 1 FROM ai_agent_dialogue_run r
        WHERE r.session_id = c.source_session_id
          AND r.request_id = c.source_request_id
          AND r.user_id IS NOT NULL
          AND r.user_id <> s.user_id
   );

-- ============================================================
-- 2) 迁移：关系判据成立时 USER -> ERP。重复执行时 WHERE 不再命中。
-- ============================================================
UPDATE ai_agent_ltm_curated_entry c
SET owner_type = 'ERP'
WHERE c.owner_type = 'USER'
  AND NOT EXISTS (SELECT 1 FROM user_account owner_account
                   WHERE owner_account.user_id = c.owner_id AND owner_account.deleted = 0)
  AND EXISTS (
      SELECT 1 FROM ai_agent_dialogue_session s
       WHERE s.session_id = c.source_session_id
         AND s.deleted = 0
         AND s.user_id IS NOT NULL
         AND s.user_id <> c.owner_id
         AND EXISTS (SELECT 1 FROM user_account session_account
                      WHERE session_account.user_id = s.user_id AND session_account.deleted = 0)
         AND NOT EXISTS (
             SELECT 1 FROM ai_agent_dialogue_run r
              WHERE r.session_id = c.source_session_id
                AND r.request_id = c.source_request_id
                AND r.user_id IS NOT NULL
                AND r.user_id <> s.user_id
         )
  );

-- ============================================================
-- 3) 验收：迁移后不应再有可证明是 ERP 兜底的 USER 行。
-- ============================================================
-- SELECT COUNT(*) AS remaining_erp_rows_under_user_type
--   FROM ai_agent_ltm_curated_entry c
--  WHERE c.owner_type = 'USER'
--    AND NOT EXISTS (SELECT 1 FROM user_account owner_account
--                     WHERE owner_account.user_id = c.owner_id AND owner_account.deleted = 0)
--    AND EXISTS (
--        SELECT 1 FROM ai_agent_dialogue_session s
--         WHERE s.session_id = c.source_session_id
--           AND s.deleted = 0
--           AND s.user_id IS NOT NULL
--           AND s.user_id <> c.owner_id
--           AND EXISTS (SELECT 1 FROM user_account session_account
--                        WHERE session_account.user_id = s.user_id AND session_account.deleted = 0)
--           AND NOT EXISTS (
--               SELECT 1 FROM ai_agent_dialogue_run r
--                WHERE r.session_id = c.source_session_id
--                  AND r.request_id = c.source_request_id
--                  AND r.user_id IS NOT NULL
--                  AND r.user_id <> s.user_id
--           )
--    );

-- ============================================================
-- 4) 回滚（按备份表逐行恢复，仅恢复本脚本改过的类型）。
-- ============================================================
-- UPDATE ai_agent_ltm_curated_entry c
-- SET owner_type = 'USER'
-- WHERE c.owner_type = 'ERP'
--   AND EXISTS (
--       SELECT 1 FROM ai_agent_ltm_curated_entry_bak_ltm_20261112 b
--        WHERE b.id = c.id AND b.owner_type = 'USER'
--   )
--   AND EXISTS (
--       SELECT 1 FROM ai_agent_dialogue_session s
--        WHERE s.session_id = c.source_session_id
--          AND s.deleted = 0
--          AND s.user_id IS NOT NULL
--          AND s.user_id <> c.owner_id
--          AND EXISTS (SELECT 1 FROM user_account session_account
--                       WHERE session_account.user_id = s.user_id AND session_account.deleted = 0)
--          AND NOT EXISTS (
--              SELECT 1 FROM ai_agent_dialogue_run r
--               WHERE r.session_id = c.source_session_id
--                 AND r.request_id = c.source_request_id
--                 AND r.user_id IS NOT NULL
--                 AND r.user_id <> s.user_id
--          )
--   );
