-- Agent timeline consolidation: additive schema only.
-- Safe to rerun after a database backup. Data rebuild is intentionally separate.

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE ai_agent_dialogue_session ADD COLUMN timeline_seq BIGINT NOT NULL DEFAULT 0 COMMENT ''会话级 committed timeline 排序高水位'' AFTER event_seq',
        'DO 0'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ai_agent_dialogue_session'
      AND column_name = 'timeline_seq'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE ai_agent_timeline_item ADD KEY idx_timeline_artifact (artifact_id)',
        'DO 0'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_agent_timeline_item'
      AND index_name = 'idx_timeline_artifact'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE ai_agent_timeline_item ADD KEY idx_timeline_tool (tool_invocation_id)',
        'DO 0'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_agent_timeline_item'
      AND index_name = 'idx_timeline_tool'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE ai_agent_timeline_item ADD KEY idx_timeline_llm (llm_invocation_id)',
        'DO 0'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_agent_timeline_item'
      AND index_name = 'idx_timeline_llm'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Existing provisional rows must never be treated as committed facts.
UPDATE ai_agent_timeline_item
SET deleted = 1,
    update_time = NOW()
WHERE deleted = 0
  AND (
      item_id LIKE 'item:%'
      OR item_id LIKE 'artifact:%-%-%-%-%'
      OR (kind = 'ARTIFACT' AND (
          artifact_id IS NULL
          OR tool_invocation_id IS NULL
          OR parent_item_id IS NULL
          OR parent_item_id NOT LIKE 'tool:%'
      ))
      OR (kind = 'TOOL_CALL' AND tool_invocation_id IS NULL)
  );

-- Rebuild run-final identity from the Ledger for terminal runs. Historical API
-- compatibility can then materialize the answer without trusting an LLM frame.
INSERT INTO ai_agent_timeline_item (
    session_id,
    run_id,
    item_id,
    parent_item_id,
    agent_id,
    kind,
    phase,
    status,
    order_key,
    sub_order,
    updated_seq,
    source_type,
    llm_invocation_id,
    tool_invocation_id,
    artifact_id,
    deleted
)
SELECT
    r.session_id,
    r.request_id,
    CONCAT('run:', r.request_id, ':final'),
    NULL,
    'main',
    'ASSISTANT_MESSAGE',
    'FINAL',
    CASE
        WHEN r.status = 1 THEN 'COMPLETED'
        WHEN r.status = 4 THEN 'CANCELLED'
        ELSE 'FAILED'
    END,
    0,
    0,
    0,
    'RUN',
    NULL,
    NULL,
    NULL,
    0
FROM ai_agent_dialogue_run r
WHERE r.deleted = 0
  AND r.status IN (1, 2, 3, 4)
  AND r.final_summary_text IS NOT NULL
  AND TRIM(r.final_summary_text) <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM ai_agent_timeline_item existing
      WHERE existing.session_id = r.session_id
        AND existing.item_id = CONCAT('run:', r.request_id, ':final')
        AND existing.deleted = 0
  );

-- Rebuild visible output artifacts from the Ledger. The history projector rebuilds
-- the parent Tool view from ai_agent_tool_invocation; the frontend drops an artifact
-- if that parent Tool cannot be resolved.
INSERT INTO ai_agent_timeline_item (
    session_id,
    run_id,
    item_id,
    parent_item_id,
    agent_id,
    kind,
    phase,
    status,
    order_key,
    sub_order,
    updated_seq,
    source_type,
    llm_invocation_id,
    tool_invocation_id,
    artifact_id,
    deleted
)
SELECT
    r.session_id,
    r.request_id,
    CONCAT('artifact:', a.id),
    CONCAT('tool:', COALESCE(a.tool_call_id, t.tool_call_id)),
    COALESCE(t.sub_agent_id, 'main'),
    'ARTIFACT',
    'INTERMEDIATE',
    'COMPLETED',
    0,
    ROW_NUMBER() OVER (
        PARTITION BY a.tool_invocation_id
        ORDER BY a.create_time, a.id
    ),
    0,
    'ARTIFACT',
    NULL,
    a.tool_invocation_id,
    a.id,
    0
FROM ai_agent_artifact a
JOIN ai_agent_dialogue_run r
  ON (a.run_id IS NOT NULL AND r.id = a.run_id)
  OR (a.run_id IS NULL AND r.request_id = a.request_id)
JOIN ai_agent_tool_invocation t
  ON t.id = a.tool_invocation_id
 AND t.run_id = r.id
 AND t.deleted = 0
WHERE a.deleted = 0
  AND r.deleted = 0
  AND r.status IN (1, 2, 3, 4)
  AND a.artifact_role = 'output'
  AND a.visibility = 'visible'
  AND t.tool_call_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM ai_agent_timeline_item existing
      WHERE existing.session_id = r.session_id
        AND existing.artifact_id = a.id
        AND existing.deleted = 0
  );

-- Repair stale run counters so the Ledger summary and timeline projection agree.
UPDATE ai_agent_dialogue_run r
SET artifact_count = (
    SELECT COUNT(*)
    FROM ai_agent_artifact a
    WHERE a.run_id = r.id
      AND a.deleted = 0
      AND a.artifact_role = 'output'
      AND a.visibility = 'visible'
)
WHERE r.deleted = 0
  AND r.status IN (1, 2, 3, 4)
  AND r.artifact_count <> (
      SELECT COUNT(*)
      FROM ai_agent_artifact a
      WHERE a.run_id = r.id
        AND a.deleted = 0
        AND a.artifact_role = 'output'
        AND a.visibility = 'visible'
  );

-- Post-migration invariant checks:
-- 1) ARTIFACT rows without artifact/tool/parent identity must return 0.
-- 2) `item:%` and provisional UUID ARTIFACT rows must return 0.
-- 3) A terminal run with final_summary_text must have exactly one run-final.
