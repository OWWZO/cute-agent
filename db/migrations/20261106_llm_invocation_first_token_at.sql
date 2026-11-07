-- 为 LLM invocation 账本补充首个有效流式增量时间。
-- 非流式调用或未观测到增量的历史行保持 NULL，不能回填为 0。
ALTER TABLE ai_agent_llm_invocation
    ADD COLUMN first_token_at DATETIME(3) NULL
    COMMENT '首个有效流式增量到达时间；非流式为空'
    AFTER started_at;
