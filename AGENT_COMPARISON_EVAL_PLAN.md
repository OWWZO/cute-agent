# Cute Agent 与 Codex CLI 对照评测开发计划

> 状态：数据集、工作区、Cute 批量适配、Codex 子进程适配、逐题 Inspect 和对照报告已实施；Bridge、Cute live 对照和完整 Pilot 待执行
> 日期：2026-11-14
> 适用项目：`D:\Java Code\ai-agent\Cute-agent`
> 主要范围：`evals/agent`
> 目标框架：Inspect AI `0.3.288`
> 参考现状：`evals/agent/src/cute_evals/inspect_runner.py`
>
> 2026-11-14 预算公平性补充：已增加 `product` / `capability` 两种预算口径。
> `product` 保留当前预算，并让 Codex App Server 在可观察软阈值上中断后强制收尾；
> `capability` 对 Cute 和 Codex 套用同一组放大预算，退出码只看官方质量。
> 原始预算、实际预算、倍率和软收尾事实均写入 Trace / 报告。
> Codex App Server 的 Token 事件粒度仍受产品实现限制；报告用
> `tokenUsageUpdates` / `tokenUsageBeforeFinal` 明示是否具备回合内观测。

## 0. 一句话结论

在现有 Cute-Agent Bench 旁增加一条独立的双 Agent 对照链路：

```text
同一份 JSON 题目
  -> Cute Agent Adapter
  -> Codex CLI Adapter
  -> 独立干净工作区
  -> 统一预算和记录格式
  -> Inspect AI 逐题 Sample
  -> 现有确定性 Scorer
  -> 现有报告体系 + Inspect Viewer
```

Inspect AI 负责运行编排、逐题日志和过程查看；项目现有的 Execution Ledger、确定性 Scorer、`CaseResult` 和 `report.py` 继续负责最终判定。不改用 Inspect 内置 Agent 冒充 Codex，也不用通用 LLM 评分替代现有 Oracle。

## 1. 目标与非目标

### 1.1 目标

1. 同一个任务可以分别交给 Cute Agent 和 Codex CLI 执行。
2. 两边使用同一份题目、同一初始工作区、同一时间预算和同一套最终评分。
3. Inspect AI 中每个 Case 对应一个 `Sample`，可以逐题查看输入、轨迹、工具调用、最终结果和评分。
4. 对外的最终结论继续由项目现有 Scorer 和报告生成，Inspect 不作为第二套结果真相源。
5. 首版支持 12 到 20 道题、至少重复 3 次，能够比较通过率、稳定性、成本和过程差异。
6. 保持现有单 Agent PR 门禁不受影响。

### 1.2 非目标

- 不把 Cute Agent 重写成 Inspect 内置 Agent。
- 不把 Codex CLI 简化为一个普通 LLM 调用。
- 不强制两个 Agent 使用完全相同的工具集。
- 不用 Inspect 的通用评分库替换项目现有确定性 Scorer。
- 不在首版实现官方排行榜、模型训练或自动修改 Prompt。
- 不在首版改写当前 `cute-evals run` 的默认执行流程。

## 2. 当前事实与设计约束

### 2.1 当前实现

- `evals/agent/src/cute_evals/inspect_runner.py` 当前把一个 Suite 包装成一个 `Sample`，在 Solver 中一次性调用 `run_java_cases`。
- `java_runner.py` 当前支持一次 Java/Maven 进程处理整组 Case。
- `cli.py` 已经有 `--repeat` 循环，`report.py` 已经有 pass@1、pass^k、baseline diff 和稳定性统计。
- `ab_runner.py` 已经有实验 Cell、重试和并行执行能力。
- Inspect AI 已锁定为 `0.3.288`，当前只使用了 `Task`、`eval`、`Sample`、`solver`、`scorer`、`accuracy`。

### 2.2 关键设计约束

1. 逐题 `Sample` 不能直接导致每道题都重新启动 Maven/JVM，否则 48 道题会产生明显的时间和资源浪费。
2. Codex CLI 的启动方式、登录方式、输出格式和模型代理能力必须先做可行性验证，不能在计划中假定其可用。
3. Cute Agent 的模型请求是否能被重定向到 Inspect 的模型代理，需要单独验证。
4. 对照评测必须区分“产品默认配置对照”和“固定条件对照”，不能混成一个结论。
5. Windows 环境使用 PowerShell；需要 Bash 时必须使用 Git Bash 绝对路径。

## 3. 两条对照赛道

### 3.1 产品默认配置对照

目标是回答“用户实际会选哪个产品”。

允许两个 Agent 使用各自默认的模型、系统提示、工具和交互方式，只控制：

- 题目相同。
- 初始工作区相同。
- 总时间上限相同。
- 统计口径相同。

该赛道的差异包含模型、工具、Prompt、上下文管理和产品交互，不能单独归因。

### 3.2 固定条件对照

目标是回答“在模型和预算基本一致时，编排和工具管理谁更强”。

首版建议先做这一条，固定或尽量固定：

- 底层模型或模型等价映射。
- 最大运行时间。
- 最大工具调用次数。
- 网络策略。
- 工作区初始状态。
- 最终评分和轨迹归一化标准。

两个赛道分别输出报告，禁止把两条赛道的分数合并成一个“总冠军”。

## 4. 总体架构

```text
compare dataset (JSON)
        |
        +----------------------+
        |                      |
        v                      v
CuteAgentAdapter       CodexCliAdapter
        |                      |
        +----------+-----------+
                   |
                   v
          ComparisonRunTrace
                   |
                   v
          Existing deterministic Scorer
                   |
                   v
       Existing report + Inspect Viewer
```

### 4.1 模块边界

- 新链路放在 `evals/agent/src/cute_evals/compare/`。
- 不修改现有 `run` 的默认行为。
- 新增独立命令，例如：

```powershell
uv run cute-evals compare `
  --dataset evals/agent/cases/compare/pilot.json `
  --agents cute,codex `
  --mode controlled `
  --repeat 3
```

- 现有 `uv run cute-evals run` 继续服务单 Agent PR 门禁。

### 4.2 Inspect 的职责

- 创建逐题 `Sample`。
- 记录每个 Agent 的运行和相关日志。
- 提供 Viewer 逐题查看能力。
- 在需要时提供模型代理、模型别名、审批和沙箱扩展点。

### 4.3 项目自有模块的职责

- 读取和锁定题目数据集。
- 管理干净工作区。
- 执行 Cute Agent 和 Codex CLI。
- 将两边的运行事实归一化为已有评分模型。
- 执行确定性 Scorer。
- 生成现有格式的 `report.json`、`report.html` 和 baseline diff。

## 5. 数据与配置设计

### 5.1 统一 JSON 题目集

新增对照评测专用 JSON 数据集，不直接复用现有 YAML 作为跨 Agent 的长期契约。

建议结构：

```json
{
  "schemaVersion": "1.0",
  "suite": "compare-pilot",
  "version": 1,
  "cases": [
    {
      "id": "compare.code.edit.01",
      "query": "修改指定文件并运行测试",
      "workspace": {
        "template": "workspaces/code-edit-01",
        "readOnly": false
      },
      "expected": {
        "finalContains": ["测试通过"],
        "requiredTools": [],
        "forbiddenTools": [],
        "requiredArtifacts": [],
        "checks": []
      },
      "budgets": {
        "maxWallMs": 180000,
        "maxTotalTokens": 60000,
        "maxToolCalls": 30
      },
      "network": {
        "mode": "allowlist",
        "hosts": []
      },
      "metadata": {
        "taskType": "code-edit",
        "risk": "medium"
      }
    }
  ]
}
```

要求：

- 两边读取完全相同的 Case ID 和输入。
- Case ID 在 Suite 内唯一。
- 数据集生成稳定哈希并写入报告。
- 缺字段时显式标记 `unverified`，不自动补造。
- 现有 Cute 专用 YAML 可以保留，但对照 Suite 使用 JSON 作为唯一真相源。

### 5.2 初始工作区

每次 Agent 执行使用全新的工作区：

```text
.eval-runs/compare/<mode>/<agent>/attempt-01/<case-id>/workspace/
```

规则：

- 从模板复制，不复用上一次 Attempt 的目录。
- 输入文件可以设为只读，输出目录必须可写。
- 每个 Case、每个 Agent、每个 Attempt 相互隔离。
- 运行结束后保留最终工作区快照，供结果检查和人工复查。
- Windows 临时路径不能包含空格；继续遵守 `CUTE_EVAL_JAVA_TMP` 的约束。

### 5.3 统一预算

首版至少支持：

- `maxWallMs`
- `maxTotalTokens`
- `maxToolCalls`
- `maxSteps`
- `network.mode`

无法从某个 Agent 获得的指标必须标记为 `unverified`，不能按 0 计算，也不能默认判定为通过。

## 6. Agent Adapter 设计

### 6.1 统一接口

新增最小 Adapter 合约，概念上包含：

```text
prepare(case, workspace)
run(case, workspace, budget)
collect_trace()
cleanup()
```

Adapter 只负责启动、限制和收集事实，不负责最终业务评分。

### 6.2 Cute Agent Adapter

首版优先复用现有 Java Runner：

- 不复制现有 Agent 执行逻辑。
- 继续通过 `run_java_cases` 或等价的单 Case 入口执行。
- 将现有 Execution Ledger `trace.json` 映射为对照评测的统一记录。
- 不修改生产 Agent 主链路。

需要重点验证：

- 单 Case 执行时是否可以不重复 Maven 编译。
- 是否能批量执行但为每个 Case 生成独立记录。
- 模型请求能否被 Inspect Bridge 接管；不能接管时，产品默认赛道和固定模型赛道的边界必须明确。

### 6.3 Codex CLI Adapter

首版必须先做可行性验证，不直接假设以下能力已经存在：

- headless 启动命令。
- 非交互式登录或 API Key 配置。
- JSON/JSONL 事件输出。
- 最终消息、工具调用、文件变更和 token 使用情况能否提取。
- 是否能设置工作区目录、超时和模型。
- 是否能接入 Inspect `sandbox_agent_bridge`。

Adapter 输出必须归一化为统一记录，不允许把 Codex 的内部格式直接传给现有 Scorer。

### 6.4 关于 `sandbox_agent_bridge`

优先用于以下能力：

- 统一模型路由和模型别名。
- 保持 `forward_generation_config=False`，减少生成参数差异。
- 按需暴露 MCP 工具。
- 记录审批、工具调用和模型事件。
- 需要时接入压缩和检查点。

前提是目标 Agent 能指向 Bridge 提供的模型端点。若 Codex 或 Cute Agent 无法稳定接入，先做普通子进程 Adapter，Bridge 只作为公平性增强项，不阻塞首个 Pilot。

## 7. 逐题 Sample 与 Viewer 设计

### 7.1 目标形态

在 Inspect 中看到：

```text
compare / controlled / codex
  sample: compare.code.edit.01
  sample: compare.code.edit.02

compare / controlled / cute
  sample: compare.code.edit.01
  sample: compare.code.edit.02
```

每个 Sample 至少包含：

- Case ID 和 Suite。
- Agent 名称和版本。
- 模型和预算。
- 初始工作区哈希。
- 最终回答。
- 工具调用和关键事件。
- 最终评分及失败分类。
- 结果是否为 `verified`、`unverified` 或 `failed`。

### 7.2 性能约束

逐题 Sample 是“查看和重试单位”，不一定等于“每道题启动一个 Java 进程”。

Phase 1 做两个方案的小规模对比：

1. **Native per-sample**：Inspect 每个 Sample 调一次 Cute/Codex。
2. **Batch + materialize**：Cute Agent 保持一次批量执行，再把每个 Case 的结果映射成独立 Sample 日志。

建议验收阈值（可在评审时调整）：

- 若逐题 Sample 使总运行时间超过当前批量的 1.5 倍，则 PR 门禁继续走批量方式。
- 1.5 倍以内再考虑默认启用逐题执行。
- Viewer 和报告都必须能从两种方式中得到相同的 Case 结果。

### 7.3 查看与判定边界

- Inspect Viewer 用于“看过程”。
- `report.json` / `report.html` 用于“做判定”。
- 任何人工结论都必须能回溯到具体 Trace 和 ScoreRecord。
- Viewer 中修改评分只用于调试，不回写正式报告。

## 8. 轨迹与评分设计

### 8.1 轨迹归一化

两个 Adapter 都输出统一字段：

- `status`
- `finalText`
- `toolInvocations`
- `artifacts`
- `contextEvents`
- `usage`
- `timing`
- `environment`

缺失事实使用 `null`、空列表或 `unverified`，不能伪造。

### 8.2 硬性判定

优先级从高到低：

1. 任务是否完成。
2. 最终文件或环境状态是否符合要求。
3. 测试、命令和官方 evaluator 是否通过。
4. 是否产生禁止的副作用。
5. 是否在预算内完成。

### 8.3 过程判定

过程指标用于解释差异，不单独决定胜负：

- 工具选择是否合理。
- 工具顺序是否符合任务。
- 是否重复调用和循环。
- 工具失败后是否恢复。
- 是否在需要时请求澄清。
- 是否有不必要的长上下文和不必要的 Skill 加载。
- 最终答案是否有引用和 Artifact。

### 8.4 统计口径

输出至少包含：

- pass@1。
- pass^k。
- 每个 Case 的稳定性。
- 中位 token、时间和首 token 延迟。
- 失败分类。
- 置信区间或明确的样本不足提示。
- Cute 与 Codex 的逐题对照表。

最终报告继续复用现有 `report.py`，只增加对照字段，不建立第二套统计实现。

## 9. 建议目录与文件改动

以下为建议，不是实施前的硬性文件清单。

```text
evals/agent/src/cute_evals/compare/
  __init__.py
  dataset.py          # JSON 读取、校验、哈希
  workspace.py        # 工作区复制、隔离、快照
  adapter.py          # 统一 Adapter 合约
  cute_adapter.py     # Cute Agent 执行与 Trace 归一化
  codex_adapter.py    # Codex CLI 执行与 Trace 归一化
  inspect_task.py     # 逐题 Sample 和 Inspect 任务
  compare_report.py   # 对照报告字段适配

evals/agent/tests/
  test_compare_dataset.py
  test_compare_workspace.py
  test_codex_adapter.py
  test_compare_inspect_task.py
  test_compare_integration.py

evals/agent/schemas/
  compare-case.schema.json
  compare-trace.schema.json
  compare-report.schema.json

evals/agent/cases/compare/
  pilot.json
```

现有文件只做必要接入：

- `cli.py`：增加 `compare` 子命令，但不改变 `run` 的默认行为。
- `inspect_runner.py`：抽离可复用的逐题 Task 构建逻辑，不改变现有单 Agent 路径。
- `models.py`：仅在确有字段缺口时扩展，不为对照评测复制一整套餐模型。

## 10. 分阶段实施

### Phase 0：可行性验证

目标是消除最大不确定性，不写正式业务代码。

- [x] 确认 Codex CLI 的实际版本、headless 启动命令和输出格式。
- [x] 确认 Codex CLI 能否在干净目录执行并返回最终文本、工具事件和 usage。
- [ ] 确认 Cute Agent 的模型配置入口，判断能否指向 Inspect Bridge。
- [ ] 确认 `sandbox_agent_bridge` 在当前 Windows 沙箱内可实际启动 Agent。
- [ ] 用一个临时 3 Case 数据集做手工 Dry Run。

通过标准：

- 两个 Agent 都能在同一个 case 上产出可解析的最终结果。
- 至少能拿到最终文本和退出状态。
- 明确哪些字段可以验证，哪些只能标记为 `unverified`。

### Phase 1：统一数据集与工作区

- [x] 定义并校验比较 Case JSON Schema。
- [x] 建立 12 到 20 道 Pilot Case，覆盖代码修改、研究、数据处理、交互和多轮任务。
- [x] 实现稳定哈希，写入环境元数据。
- [x] 实现模板复制、只读输入、独立输出和最终快照。
- [x] 验证两个 Agent 拿到完全相同的初始文件树。

验证：

```powershell
uv run cute-evals compare validate --dataset evals/agent/cases/compare/pilot.json
uv run cute-evals compare materialize --dataset evals/agent/cases/compare/pilot.json
```

验收：

- 数据集重复读取得到相同哈希。
- 每个 Case 的工作区路径互不重叠。
- 两次运行之间没有残留文件。

### Phase 2：Cute Agent Adapter

- [x] 复用现有 Java Runner，不复制 Agent 主链路。
- [x] 将 Execution Ledger 结果映射为统一 Trace。
- [x] 保留实际 Token、时间、步骤、工具和 Artifact 事实。
- [x] 缺失字段显式标记 `unverified`。
- [x] 增加单元测试和现有 Fixture 回归。

验收：

- 单 Case 与多 Case 的评分结果一致。
- Cute Agent 原有 PR 门禁不受影响。
- 现有 `CaseResult` 和报告字段可继续使用。

### Phase 3：Codex CLI Adapter 与 Bridge

- [x] 实现最小 Codex CLI 子进程 Adapter。
- [x] 解析最终回答、工具事件、文件变更和可获得的 usage。
- [x] 实现超时、退出码、异常和进程清理。
- [ ] 先验证普通子进程方式，再验证 `sandbox_agent_bridge`。
- [ ] 固定模型别名，默认不转发客户端生成参数。
- [x] 记录 Codex 版本、认证模式和模型配置，但不写入密钥。

验收：

- 3 个 Smoke Case 可以稳定完成。
- 失败时有结构化失败分类，而不是只有原始 stderr。
- Bridge 不可用时可以显式降级为普通子进程 Adapter。

### Phase 4：逐题 Inspect Task 与 Viewer

- [x] 用同一个数据集为两个 Agent 创建独立任务。
- [x] 每个 Case 对应一个 Sample。
- [x] 为每个 Sample 写入 Agent、配置、预算、Trace 和 ScoreRecord。
- [ ] 实现 Native per-sample 与 Batch + materialize 的耗时对比。
- [ ] 根据 1.5 倍阈值决定 PR 门禁执行策略。
- [x] 在文档中写明 Viewer 启动方式和日志目录。

验收：

- 每个 Case 都能在 Viewer 中单独打开。
- 运行失败、评分失败和结构缺失可区分。
- Viewer 中看到的结果与 `report.json` 一致。

### Phase 5：对照报告与 Pilot

- [x] 在现有报告上增加 Cute/Codex 对照区。
- [x] 增加产品默认和固定条件两种模式标签。
- [x] 增加逐题 winner、稳定性、成本和失败原因。
- [x] 默认只把最终结果和环境状态用于胜负，轨迹用于解释。
- [ ] 跑 12 到 20 道题，至少重复 3 次。
- [ ] 人工抽查至少 3 组轨迹差异，确认自动结论可解释。

输出：

```text
.eval-runs/compare/controlled/
  report.json
  report.html
  inspect-logs/
  attempts/
```

验收：

- 可以回答“哪个 Agent 更好”，并说明差异来自结果、过程还是预算。
- 单次偶然失败不会被写成整体能力差距。
- 样本不足时明确提示，不输出过度结论。

## 11. 测试计划

### 11.1 单元测试

- JSON Schema 校验。
- 数据集稳定哈希。
- 工作区复制和隔离。
- 预算字段映射。
- Trace 归一化。
- Codex 输出解析。
- 失败分类和 `unverified` 传播。

### 11.2 集成测试

- 使用 Fake Codex 可执行文件模拟成功、超时和异常输出。
- 使用现有 Fixture 模型运行 Cute Agent。
- 两边都进入同一个 Scorer 和报告生成器。
- 验证每个 Case 产生独立 Sample。
- 验证工作区快照和日志目录存在。

### 11.3 回归测试

```powershell
cd "D:\Java Code\ai-agent\Cute-agent\evals\agent"
uv sync --frozen --extra dev --extra frameworks
.\.venv\Scripts\python.exe -m pytest -o addopts="" -q tests --tb=no
```

`uv run cute-evals run --profile pr --suite all` 必须保持当前行为，不得因为新增 `compare` 命令而改变 baseline 或报告格式。

## 12. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| Codex CLI 无法稳定 headless 登录 | 无法自动跑 | Phase 0 先验证；必要时仅支持 API Key 模式 |
| Cute Agent 无法接入 Inspect Bridge | 固定模型对照不成立 | 先做产品默认赛道；固定模型赛道标记为不可用 |
| 逐题 Sample 导致 Maven/JVM 启动过多 | 总耗时明显增加 | 保留批量执行，采用 Batch + materialize；实测后再决定 |
| 两 Agent 工具差异过大 | 对照不公平 | 分成产品默认和固定条件两赛道，不混算总分 |
| Codex token/usage 无法获取 | 成本指标不完整 | 标记 `unverified`，不按 0 计算 |
| Windows 进程清理不彻底 | 端口、临时文件和子进程残留 | 使用进程树终止、独立临时目录和启动前检查 |
| Inspect 日志与项目报告不一致 | 结论冲突 | 项目报告作为正式真相源，Viewer 仅用于查看过程 |
| 安全边界不清 | 误暴露账号或环境 | 使用独立工作区、最小环境变量、必要审批和网络 allowlist |

## 13. 待确认问题

以下问题不阻塞写计划，但必须在 Phase 0 或 Phase 1 前定案：

1. 首个 Pilot 先跑“产品默认配置”还是“固定条件”？建议先跑固定条件，再跑产品默认。
2. Codex CLI 是否允许使用现有登录态，还是只使用独立 API Key？
3. 首版是否强制 Docker，还是允许本机干净工作区先验证？
4. Cute Agent 的模型请求能否指向 Inspect Bridge？
5. Pilot 的 12 到 20 道题中，代码、研究、数据、交互和安全各占多少比例？
6. 是否允许联网？若允许，allowlist 由谁维护？

## 14. 实施顺序与完成定义

推荐顺序：

1. Phase 0 可行性验证。
2. Phase 1 数据与工作区。
3. Phase 2 Cute Adapter。
4. Phase 3 Codex Adapter。
5. Phase 4 Inspect Viewer。
6. Phase 5 对照报告与 Pilot。

整体完成定义：

- `compare` 命令可以启动两个 Agent。
- 两边使用同一数据集、同一工作区模板和可解释的预算。
- Inspect 中能逐题查看轨迹。
- 最终分数来自现有确定性 Scorer 和报告体系。
- 至少完成一次 12 到 20 道题、3 次重复的 Pilot。
- 报告能区分“产品差异”“编排差异”“运行失败”“样本不足”。
- 现有 `cute-evals run` 的 PR 门禁与 baseline 行为不变。
