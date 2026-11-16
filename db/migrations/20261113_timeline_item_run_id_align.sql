-- 20261113: align ai_agent_timeline_item with the live schema.
--
-- Live MySQL uses run_id and session-scoped item identity. Older installs created
-- by 20261104_agent_timeline_item.sql may still have request_id and run-scoped
-- indexes. This script is idempotent and only changes the timeline directory.
--
-- Manual execution (the project has no Flyway/Liquibase). Before creating the
-- unique session item index, timeline_duplicate_session_item_groups must be 0.
-- Do not delete historical rows automatically.

-- 1) Rename request_id to run_id when only the old column exists.
SET @timeline_has_request_id = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'ai_agent_timeline_item'
       AND column_name = 'request_id'
);
SET @timeline_has_run_id = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'ai_agent_timeline_item'
       AND column_name = 'run_id'
);
SET @timeline_rename_sql = IF(
    @timeline_has_request_id = 1 AND @timeline_has_run_id = 0,
    'ALTER TABLE ai_agent_timeline_item CHANGE COLUMN request_id run_id VARCHAR(64) NOT NULL COMMENT ''所属 requestId / runId''',
    'SELECT ''run_id column already aligned'' AS migration_result'
);
PREPARE timeline_rename_stmt FROM @timeline_rename_sql;
EXECUTE timeline_rename_stmt;
DEALLOCATE PREPARE timeline_rename_stmt;

-- 2) Remove the old run-scoped index names after the column rename.
SET @timeline_drop_old_unique_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'ai_agent_timeline_item'
        AND index_name = 'uk_timeline_request_item') > 0,
    'ALTER TABLE ai_agent_timeline_item DROP INDEX uk_timeline_request_item',
    'SELECT ''old unique index already absent'' AS migration_result'
);
PREPARE timeline_drop_old_unique_stmt FROM @timeline_drop_old_unique_sql;
EXECUTE timeline_drop_old_unique_stmt;
DEALLOCATE PREPARE timeline_drop_old_unique_stmt;

SET @timeline_drop_old_run_index_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'ai_agent_timeline_item'
        AND index_name = 'idx_timeline_request_order') > 0,
    'ALTER TABLE ai_agent_timeline_item DROP INDEX idx_timeline_request_order',
    'SELECT ''old run index already absent'' AS migration_result'
);
PREPARE timeline_drop_old_run_index_stmt FROM @timeline_drop_old_run_index_sql;
EXECUTE timeline_drop_old_run_index_stmt;
DEALLOCATE PREPARE timeline_drop_old_run_index_stmt;

-- 3) Verify there is no duplicate (session_id, item_id, deleted) before adding
-- the session-scoped unique key.
SELECT session_id, item_id, deleted, COUNT(*) AS duplicate_count
  FROM ai_agent_timeline_item
 GROUP BY session_id, item_id, deleted
HAVING COUNT(*) > 1;

SET @timeline_session_item_unique_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'ai_agent_timeline_item'
        AND index_name = 'uk_timeline_session_item') = 0,
    'CREATE UNIQUE INDEX uk_timeline_session_item ON ai_agent_timeline_item (session_id, item_id, deleted)',
    'SELECT ''uk_timeline_session_item already exists'' AS migration_result'
);
PREPARE timeline_session_item_unique_stmt FROM @timeline_session_item_unique_sql;
EXECUTE timeline_session_item_unique_stmt;
DEALLOCATE PREPARE timeline_session_item_unique_stmt;

-- 4) Restore the run-order index under the live schema name.
SET @timeline_run_order_index_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'ai_agent_timeline_item'
        AND index_name = 'idx_timeline_run_order') = 0,
    'CREATE INDEX idx_timeline_run_order ON ai_agent_timeline_item (run_id, deleted, order_key, sub_order, id)',
    'SELECT ''idx_timeline_run_order already exists'' AS migration_result'
);
PREPARE timeline_run_order_index_stmt FROM @timeline_run_order_index_sql;
EXECUTE timeline_run_order_index_stmt;
DEALLOCATE PREPARE timeline_run_order_index_stmt;
