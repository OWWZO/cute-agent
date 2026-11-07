-- 运维时间窗导出按 (create_time, id) keyset 分页读取 run 总账。
ALTER TABLE ai_agent_dialogue_run
    ADD KEY idx_dialogue_run_create_cursor (deleted, create_time, id);
