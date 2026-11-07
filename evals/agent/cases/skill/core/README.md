# skill-sim

`skill-sim` 是 Cute-Agent Bench 自有的本地 skill 模拟评测，不是 SkillsBench
官方评测，也不使用 Docker、官方镜像或官方 verifier。

它覆盖五类行为：

- 正确选择需要的 skill。
- 在多个候选 skill 中抵抗干扰项。
- skill 加载失败后能否恢复。
- 不需要 skill 时是否保持克制。
- skill 加载次数、步骤和 token 预算。

PR 使用 Java Fixture Runner：固定脚本提供模型动作，但 Agent 控制循环、工具管线、
Execution Ledger 和 Trace 仍走真实 Java 实现。live 模型能力不在这个 Suite 中冒充。
