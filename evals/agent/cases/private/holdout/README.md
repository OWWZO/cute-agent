# Private Holdout Suite

本目录用于保存不会进入 PR 主回归和 Prompt 调参流程的私有留出集。

规则：

1. Holdout 只在 Release 或显式的模型能力评测中运行。
2. 不用 Holdout 结果迭代 Prompt；发现失败后只记录，不直接针对题目修改。
3. Holdout 题目、标签和运行结果不得进入公开 Benchmark。
4. YAML 默认被 Git 忽略，只提交此说明文件。
