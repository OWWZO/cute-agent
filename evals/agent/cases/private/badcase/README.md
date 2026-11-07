# Private Badcase Suite

本目录存放从真实 Execution Ledger Trace 生成的私有 Regression Case。

工作流：

1. 使用 `cute-evals ledger-export` 导出脱敏 Trace。
2. 使用 `cute-evals case-draft` 生成草稿。
3. 人工确认失败分类、Oracle 和预算；需要重新执行 Agent 时再补 `script`。
4. 将 `oracleReviewed` 改为 `true` 后，运行 `cute-evals private-replay`。

真实 Case YAML 默认被 Git 忽略，不得进入公开 Benchmark，也不得提交原始敏感 Trace。
