# Agent 评测系统最终设计与分阶段落地计划

> 状态：阶段一、1.5、2–4 子集已完成；2026-11-07 增加 `official-full` 执行开关、
> 生产型内部题库、私有 holdout、子目标进度、参考路线指标，以及 P1 运行守卫
> （共享预算、强制收尾、质量/SLO 双指标、网络断路）
> 适用项目：Cute Agent（Java + React）
> 核心事实源：Execution Ledger / Run 执行账本
> 日期：2026-11-07

---

## 0. 先给结论

这套评测体系分两层建设：

```text
第一层：评测基础设施
  让任何 Case、任何 Benchmark、任何 Agent 都能被同一套方式执行、采轨迹、评分、出报告

第二层：评测内容
  内部 ReAct / RAG / 数据分析 Case
  公开 Benchmark：tau²-bench、Terminal-Bench、WebArena、GAIA、SWE-bench Lite 等
```

最终形态：

> **Java 保留真实 Agent Runtime；Python 做评测侧车；Inspect AI 作为唯一主 Runner；Execution Ledger 导出统一 Trace；确定性 Scorer 负责执行事实；DeepEval 负责 core Case 的语义质量；agentevals 负责 ReAct 轨迹评分；Ragas/MRAG 负责 RAG；公开 Benchmark 通过标准 Adapter 接入，而不是每个 Benchmark 重造一套 CLI。**

第一阶段不是“先跑几个公开榜”，而是先完成：

1. Ledger 能导出稳定、版本化、可复现的 Trace。
2. Java 有一个测试专用执行入口。
3. Python 能在 Windows 原生环境调用 Java，不依赖 WSL。
4. 10 个 ReAct Case 能覆盖成功、工具失败、恢复、死循环和预算。
5. DeepEval、agentevals、MRAG、Ragas 的结果都能转换成统一 `ScoreRecord`。
6. PR / Nightly / Release 三层门禁能独立运行。
7. 报告能定位到具体 Case、step、LLM 调用、工具调用和 artifact。

只有这 7 件事稳定后，才接公开 Benchmark。否则公开榜失败时，无法判断是 Agent 不行、环境不对、Adapter 错了，还是评分脚本错了。

### 第一阶段实施状态（2026-11-06）

| 能力 | 状态 | 实现 |
|---|---|---|
| `firstTokenAt` Ledger 全链路 | 已实现 | Runtime、ViewModel、Mapper、Schema、迁移、回归测试 |
| Java Fixture Runner | 已实现 | 真实 `ReActAgent` 控制循环、工具管线、Ledger、Trace 导出 |
| Java Live Runner | 已实现，需独立评测库 | 非 Web Spring Context、`eval` profile、拒绝 dev/prod |
| Inspect AI 主 Runner | 已实现 | Suite-level Solver / Scorer，日志与统一报告 |
| 10 个 ReAct Case | 已实现 | 成功、串行、空结果、超时、429、畸形 JSON、权限、纠错 |
| 确定性 Scorer | 已实现 | 顺序轨迹、恢复、step、budget、artifact、citation、SQL |
| agentevals | 已接入 | deterministic trajectory match 执行；LLM judge advisory |
| DeepEval | 已实测 | `group/deepseek` 本地 Judge；完整 12 Case 硬门禁通过；内容哈希缓存 |
| Ragas / MRAG | 已实测 | MRAG citation recall + Ragas faithfulness；RAG Case 通过 |
| 重复运行与统计 | 已实现 | PR 1 次，Nightly/Release 默认 3 次，pass@1 / pass^k |
| baseline diff | 已实现 | regression、recovery、Case 增删、token / latency delta |
| PR CI | 已实现 | GitHub Actions 独立 Job，10 分钟预算 |
| 真实轨迹回流 | 已完成 | 19 条真实 Run 导出；5 个已确认 Badcase；统一 `private-replay` 稳定通过 |

当前 PR 结果：12/12 Case 通过，`pass@1=100%`、`pass^k=100%`。
使用本地 OpenAI 兼容 Judge `group/deepseek` 时，DeepEval、agentevals LLM judge、
Ragas 均为 `verified`；未配置裁判模型时仍保持 `unverified`，不会伪造通过分数。

### 2026-11-07 全量与内部题库增强

- `cute-evals benchmark --split official-full` 可显式选择官方完整任务集；
  `official-subset` 保持默认，避免历史报告含义漂移。
- GAIA 全量加载固定 revision 的全部 validation；validation 本地分数仍不能冒充
  GAIA test 或官网榜单成绩。
- tau² 全量按 `airline / banking_knowledge / retail / telecom` 四个官方 domain
  合并，内部 task id 使用 `<domain>:<id>` 防止冲突。
- BIRD 全量选择官方 500 条 Mini-Dev；本地数据库模式要求完整 11 个数据库快照，
  并生成 `database-lock.json` 记录实际哈希。
- Spider2 全量采用 fail-closed：官方 547 题资产不完整时拒绝运行，不会静默漏题。
- 新增 `production-core` 24 题和私有 `holdout-core` 16 题；PR 的 `all` 只扩入
  production，holdout 单独在 release/显式运行中使用。
- 新增 `metadata.role / taskType / lengthBucket / toolCountBucket / risk` 分层，
  报告输出 `summary.strata`。
- 新增 `subgoals`、`referenceToolSequence`，计算 `subgoal_progress` 和
  route precision / recall / F1；路线分数默认 advisory，不替代任务 Oracle。
- 报告新增 Wilson 95% 置信区间，避免把 16 到 24 题上的小样本波动当成确定结论。
- 新增 `cute-evals ops overfit`：对比 validation 与 holdout，检查重复题、克隆题、
  `pass@1` / `pass^k` 差距、token 和耗时偏差；有泄漏时直接判定为 `leakage`。
- 内部题库扩展为三层：24 条 production Case、16 条私有 holdout、真实 Trace
  人工确认后的 private badcase。Holdout 不参与 Prompt 调参，只用于发布前泛化判断。
- Fixture 验证：`--suite all` 48/48；production + GEval 24/24；holdout 16/16；
  `ops overfit` 当前为 `no-signal`，`pass^k` 差距 0。DeepEval 只评最终答案，
  工具顺序与过程由确定性 Scorer / agentevals 负责，避免裁判误判工具轨迹。

---

## 1. 评测系统的目标与非目标

### 1.1 核心目标

| 目标 | 要回答的问题 |
|---|---|
| 结果正确性 | Agent 有没有真正把任务做成，而不是“像做成了” |
| ReAct 轨迹 | 工具是否选对、参数是否正确、失败能否恢复、有没有死循环 |
| 工具稳定性 | 每类工具的成功率、失败原因、重试率、恢复率是多少 |
| 效率 | token、总耗时、首 token 延迟、工具调用次数、单任务成本 |
| 稳定性 | 同一版本重复运行是否稳定，`pass@1` 与 `pass^k` 如何 |
| 领域质量 | RAG 引用是否可信，数据分析结果是否来自真实数据 |
| 安全 | 是否越权、泄漏、被提示注入、调用危险工具 |
| 可复现 | 给定 Case、模型、数据和配置，能否复跑同一结果 |
| 可扩展 | 新领域只加 Case/Fixture/Scorer，不改 Runner 和产品 Runtime |

### 1.2 明确不做

- 不把 SSE 当评测事实源。
- 不把前端 timeline 行模型当 Execution Trace。
- 不在生产环境新增评测 HTTP 接口。
- 不让 LLM Judge 判断“工具是否真的执行过”。
- 不把公开 Benchmark 的改造子集冒充官方全量分数。
- 不在 PR 中运行高成本 VM、Web、SWE 全套 Benchmark。
- 不为了一个 Benchmark 复制一套 Java Runtime 或 Python 执行器。
- 不在 Trace、Adapter、Oracle 还不稳定时同时铺开多个评测框架。

---

## 2. 术语与边界

| 名词 | 含义 | 本项目选择 |
|---|---|---|
| 评测框架 | 负责加载 Case、执行 Solver、调用 Scorer、生成报告 | Inspect AI 主 Runner |
| 评测库 | 提供某种评分能力，不负责完整调度 | DeepEval、Ragas、agentevals |
| Benchmark | 由外部维护的任务集 + 环境 + 官方评分方式 | tau²-bench、GAIA、SWE-bench 等 |
| Suite | 一组服务于同一目标的 Case 集合 | `react-core`、`rag-core`、`data-core` |
| Case | 一次可独立执行和判分的任务 | 一个 YAML / 一个 Benchmark task |
| Adapter | 把外部 Case/Benchmark 接到本项目 Agent 的桥 | BenchmarkAdapter / AgentAdapter |
| Trace | 一次 Run 的完整执行事实快照 | Ledger 导出的 `trace.json` |
| Oracle | 判断任务成功的权威依据 | 测试、DB、DOM、文件、规则、Judge |
| Gate | 什么失败会阻塞 PR / Release | 按 profile 和 Case 定义 |

关键区别：

> Benchmark 是题库和环境；评测框架是流水线；本项目自己的 `react-core` 和领域 Case 才是产品发布门禁。

公开 Benchmark 用来校准能力、发现短板和对比业界；内部 Case 用来判断“这个版本能不能上线”。

### 2.1 用“考试”理解整套系统

| 考试概念 | 对应实现 |
|---|---|
| 考场 | 独立测试库、临时 workspace、Docker/VM/浏览器环境 |
| 考生 | `Cute-agent`，通过唯一 AgentAdapter 接入 |
| 考卷 | 内部 Case 或公开 Benchmark 的原始 task / dataset |
| 监考记录 | Execution Ledger 导出的 `trace.json` |
| 判卷老师 | 确定性 Scorer、agentevals、DeepEval、Ragas、官方 evaluator |
| 成绩单 | `report.json`、`report.html`、baseline diff |
| 考试规则 | PR / Nightly / Release 三种 profile 和门禁 |
| 题库管理 | Suite registry、Benchmark manifest、版本和 hash |

这里的“Java 通过端口暴露轨迹”只是一个抽象约定：Java 侧有一个稳定的评测 Trace 导出接口，
第一版由测试专用 CLI 实现并输出 `trace.json`，不是生产 HTTP 接口，也不是 SSE。

---

## 3. 已确认的设计决策

| 编号 | 决策 | 结论 |
|---|---|---|
| D1 | 评测事实源 | 只读 Execution Ledger / Run 执行记录，不读 SSE |
| D2 | 首 token 延迟 | Ledger 增加 `firstTokenAt`，由 Runtime 在首个有效增量到达时记录 |
| D3 | Java / Python 分工 | Java 跑真实 Runtime，Python 做编排、评分、报告和 Benchmark Adapter |
| D4 | Python 主调度 | Inspect AI 是唯一主 Runner |
| D5 | DeepEval 定位 | 仅固定 core Case 做 PR 硬门禁；其他 Case 只做 advisory |
| D6 | ReAct 轨迹评分 | 第一版加入 agentevals，先 advisory；确定性预期序列可先硬门禁 |
| D7 | RAG | 复用现有 MRAG 评测，Ragas 只作为评分库 |
| D8 | 数据分析 | 以 SQL 结果、数据集事实、artifact 为 Oracle，不只看最终文本 |
| D9 | Trace 导出 | 测试专用 Java CLI / Test Harness，不新增生产 HTTP 接口 |
| D10 | 工具故障 | 只在评测 Fixture/Adapter 层注入，不改生产工具 |
| D11 | 公开 Benchmark | 数据、题目、标签、工具和 Oracle 原样使用；不得改造；子集必须标 `official-subset` |
| D12 | 环境 | PR 用固定 fixture；Nightly 用真实模型；Release 用隔离环境 |
| D13 | 报告 | 机器结果 `report.json`，人看结果 `report.html`，另做 baseline diff |
| D14 | 基线 | 固定模型 ID、模型配置、Case 版本、数据 hash、Benchmark commit |
| D15 | 可复现 | `uv.lock`、数据集 SHA256、容器 digest、随机种子、预算全部记录 |
| D16 | Agent 身份 | 只维护 Cute Agent 一套 Runtime 和评测入口，不建立第二套 Adapter |
| D17 | 公开 Benchmark 数据 | 原始 task、dataset、标签、工具 schema、环境、官方 Oracle 一律不修改 |
| D18 | 项目专属 Benchmark | 要改数据、补题、改判分或造新 Oracle，必须建 `Cute-Agent Bench`，不得挂公开 Benchmark 名 |

### 3.0 公开 Benchmark 不可修改铁律

公开 Benchmark 只允许在外部增加兼容 Adapter：

- 不修改题目文本。
- 不修改输入文件、附件和数据库快照。
- 不修改标准答案、标签、用户模拟器目标或测试脚本。
- 不修改官方工具定义和函数 schema。
- 不修改官方 evaluator 的判定逻辑。
- 不为了让 Agent 通过而裁剪失败样本。
- 不把调试用的 prompt、few-shot 或答案拼进官方 task。

如果 Agent 无法接入：

1. 先检查是否只是接口兼容问题，可以在 Adapter 中做无损映射。
2. 如果是产品能力不兼容，记录为失败或跳过，并明确原因。
3. 如果需要对题目、数据、工具或 Oracle 做任何修改，停止使用该公开 Benchmark
   的分数，另建 `Cute-Agent Bench` 条目。

允许运行官方数据子集，但必须：

- 数据集本身不修改。
- 只通过 task ID 选择。
- 使用官方 evaluator。
- 报告标记为 `official-subset`，不能写成全量官方成绩。

### 3.1 DeepEval 的准确边界

DeepEval 只回答“语义质量是否达标”，不回答“工具是否真的调用成功”。

顺序必须是：

```text
Ledger 事实检查
  -> 确定性 Scorer
  -> agentevals 轨迹 Scorer
  -> DeepEval 语义 Scorer
  -> 最终 Gate
```

约束：

- 只有 `hardGate=true` 的 core Case 会因 DeepEval 失败阻塞 PR。
- 非 core Case 的 DeepEval 分数只记录。
- 裁判模型固定版本、温度 0、结果缓存。
- 裁判超时、网络错误、空结果、不可解析结果一律 `unverified`。
- core Case 的 `unverified` 不能默认通过。

### 3.2 agentevals 的准确定位

agentevals 评的是轨迹，不是最终世界状态：

- 工具选择是否合理。
- 工具调用顺序是否符合任务。
- 是否绕路、重复调用、漏掉关键步骤。
- 最终回答是否与轨迹一致。

它不能替代：

- 文件是否存在。
- 数据库是否被正确修改。
- SQL 结果是否正确。
- 页面最终 URL/DOM 是否正确。
- 测试是否通过。

第一版把 agentevals 作为 `TrajectoryScorer`：

- 对固定工具序列的 Case，可用确定性 exact/order 模式做硬门禁。
- LLM 轨迹 judge 先 advisory。
- 在人工标注集校准后，再逐步提升为 Nightly / Release 门禁。

---

## 4. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│ Python 评测侧车：evals/agent                                 │
│                                                              │
│  YAML Case / Benchmark Task                                  │
│            │                                                 │
│            ▼                                                 │
│      Inspect AI Runner                                       │
│            │                                                 │
│            ▼                                                 │
│      AgentAdapter                                            │
│            │                                                 │
│            └─ CuteAgentAdapter  ──> Java test CLI           │
└────────────┬─────────────────────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────────────┐
│ Java 产品运行时                                              │
│                                                              │
│  AgentExecuteStrategy / ReAct / Plan-Solve                   │
│  LLM Runtime                                                 │
│  Tool Runtime / MCP / Browser / Shell / Data                 │
│  Execution Ledger                                            │
│    ├─ DialogueRun                                            │
│    ├─ LlmInvocation                                          │
│    ├─ ToolInvocation                                         │
│    └─ Artifact                                               │
└────────────┬─────────────────────────────────────────────────┘
             │ trace.json / result.json
             ▼
┌──────────────────────────────────────────────────────────────┐
│ Python 评分与环境层                                          │
│                                                              │
│  Deterministic Scorer                                        │
│  agentevals Trajectory Scorer                                │
│  DeepEval Semantic Scorer                                    │
│  Ragas / MRAG Scorer                                         │
│  Benchmark Official Oracle                                   │
└────────────┬─────────────────────────────────────────────────┘
             │
             ▼
 report.json + report.html + baseline diff + CI exit code
```

核心原则：

- 产品运行时不依赖 Python。
- Python 评测不重写 Agent 循环。
- Ledger 是唯一执行事实源。
- 所有框架输出先转成项目自己的 `ScoreRecord`。
- 所有外部 Benchmark 输出先转成项目自己的 `TaskResult`。
- live / replay 不参与产品评分，只保留为后续前端契约测试的独立领域。

---

## 5. Execution Ledger 与 Trace 契约

### 5.1 Ledger 读取内容

第一版只通过测试专用 Java CLI 导出：

- `DialogueRun`：run 状态、开始/结束时间、总耗时、总 token、错误码。
- `LlmInvocation`：模型名、调用类型、流式标记、token、状态、耗时、首 token 时间。
- `ToolInvocation`：工具名、入参、结果、状态、错误、耗时、父调用、子 Agent。
- `Artifact`：产物名称、类型、hash、存储位置、关联工具调用。
- Run 摘要：步数、LLM 调用数、工具调用数、产物数、重试次数。

### 5.2 规范化 Trace

```json
{
  "schemaVersion": "1.0",
  "producer": {
    "agent": "cute-agent",
    "runtimeVersion": "git-sha",
    "exportedAt": "2026-11-05T10:00:12.000"
  },
  "run": {
    "runId": 123,
    "requestId": "req-...",
    "sessionId": "session-...",
    "entryAgent": "react",
    "status": "SUCCESS",
    "startedAt": "2026-11-05T10:00:00.000",
    "finishedAt": "2026-11-05T10:00:12.000",
    "durationMs": 12000,
    "promptTokens": 9000,
    "completionTokens": 1800,
    "totalTokens": 10800
  },
  "llmInvocations": [],
  "toolInvocations": [],
  "artifacts": [],
  "environment": {
    "profile": "pr",
    "fixtureId": "timeout-once",
    "datasetHash": "sha256:...",
    "modelId": "fixed-model-version"
  }
}
```

要求：

- Trace 版本独立于前端 SSE 协议。
- 时间统一时区和精度；日期使用 ISO-8601。
- 缺值必须是 `null`，不能伪造为 0。
- `unverified` 是正式结果，不是错误日志。
- 需要逐帧评测时，再加可选 `events` 字段，不破坏已有 Scorer。

### 5.3 `firstTokenAt` 改动

现状：Ledger 有 `startedAt`、`finishedAt`、`durationMs`，没有首 token 时间。

第一阶段改动：

1. `LlmInvocation` 增加 `firstTokenAt`。
2. `LlmInvocationView` 增加 `firstTokenAt`。
3. `LlmInvocationFinishRecord` 增加 `firstTokenAt`。
4. Runtime 在首个非空 content、reasoning 或 tool-call delta 到达时记录一次。
5. 终态写入 Ledger。
6. MyBatis Mapper、Schema、数据库迁移同步。
7. 非流式调用保持 `null`。
8. 流式但丢失 `firstTokenAt` 时，指标记 `unverified`，不能记 0ms。

计算：

```text
firstTokenMs = firstTokenAt - startedAt
```

这条改动只增加观测字段，不改变 Agent 控制流和用户可见输出。

---

## 6. Python 评测侧车

### 6.1 目录

```text
evals/agent/
├── pyproject.toml
├── uv.lock
├── README.md
├── src/cute_evals/
│   ├── cli.py
│   ├── adapter/
│   │   ├── java_cli.py
│   │   ├── agent_adapter.py
│   │   └── trace_loader.py
│   ├── models/
│   │   ├── case.py
│   │   ├── trace.py
│   │   ├── result.py
│   │   └── score.py
│   ├── benchmarks/
│   │   ├── base.py
│   │   ├── manifest.py
│   │   ├── tau2/
│   │   ├── terminal_bench/
│   │   ├── webarena/
│   │   ├── gaia/
│   │   └── swebench/
│   ├── tasks/
│   │   ├── react_core.py
│   │   ├── rag.py
│   │   └── data_analysis.py
│   ├── scorers/
│   │   ├── deterministic.py
│   │   ├── trajectory_agentevals.py
│   │   ├── deepeval_scorer.py
│   │   ├── ragas_scorer.py
│   │   └── mrag_scorer.py
│   ├── fixtures/tools/
│   ├── report/
│   └── ci.py
├── cases/
│   ├── react/core/
│   ├── rag/
│   └── data/
└── tests/
```

评测依赖独立安装，不进入 `cute-tool` 生产环境。

### 6.2 Python 依赖

| 依赖 | 用途 | 阶段 |
|---|---|---|
| `inspect-ai` | Case、Solver、Scorer、日志、报告调度 | 第一阶段 |
| `deepeval` | core Case 语义硬门禁 | 第一阶段 |
| `agentevals` | ReAct 轨迹 judge / 轨迹匹配 | 第一阶段 |
| `pydantic` | Trace / Case / Score 模型 | 第一阶段 |
| `typer` | CLI | 第一阶段 |
| `pyyaml` | YAML Case | 第一阶段 |
| `jinja2` | HTML 报告 | 第一阶段 |
| `pytest` | Adapter/Scorer/报告测试 | 第一阶段 |
| `ragas` | RAG 评分 | 第一阶段 RAG 子阶段 |
| `langfuse` / `opik` | 观测平台 | 后续可选 |

所有依赖锁定版本；模型 ID、Judge prompt、Case schema 和 Benchmark commit 也要锁。

### 6.3 Inspect AI 的职责

Inspect AI 只做编排：

- 加载 `Sample`。
- 执行 `CuteAgentSolver`。
- 把 Trace 放进 `state.metadata["trace"]`。
- 调用 deterministic / agentevals / DeepEval Scorer。
- 生成 Inspect 日志。

不负责：

- 真实 LLM 调用。
- ReAct 循环。
- 修改产品数据库。
- 作为唯一报告格式。

最终报告仍由项目自己的 `report.json` 和 `report.html` 输出。

### 6.4 Java 测试专用 CLI

第一版建议接口：

```text
java -jar eval-runner.jar run-case
  --case cases/react/core/001-single-tool.yaml
  --fixture fixtures/tools/success.json
  --profile pr
  --out .eval-runs/<run-id>/
```

输出：

```text
<run-id>/
├── trace.json
├── result.json
├── stdout.log
├── stderr.log
└── artifacts/
```

约束：

- 只在 test profile 启用。
- 每次运行使用唯一测试库或唯一 schema。
- 运行结束清理临时文件、数据库和 fixture。
- Python 使用参数数组调用进程，不通过 shell 拼接。
- Windows 使用 `java.exe` / `mvn.cmd`，不依赖 WSL。
- CLI 退出码必须区分：Case 失败、基础设施失败、超时、Adapter 失败。

### 6.5 Java 与 Python 的稳定边界

可以把 Java 侧的 `EvalTraceExporter` 理解为“评测端口”，但它有两种实现：

- 第一版：测试专用 CLI，运行后导出 `trace.json`。
- 将来：如果确实需要进程内调用，再增加本地测试 IPC / 端口适配器。

无论哪种实现，都不把生产 HTTP 接口作为评测入口。

Python 不允许直接依赖：

- Java Mapper 字段。
- 产品内部实体类。
- 具体数据库表结构。
- SSE `messageType` / `resultMap`。
- 前端 timeline 行模型。

Python 只依赖：

- `trace.schema.json`
- `result.schema.json`
- 进程退出码
- artifact 文件路径和 hash

这样产品改名时只需要同步名称和路径，不需要复制 Python 评测或维护第二套评分逻辑。

---

## 7. 统一 Case、Trace、Score、Result

### 7.1 Case 文件

```yaml
id: react.tool.timeout-recovery
suite: react-core
version: 1
hardGate: true

input:
  message: "读取配置并总结..."

fixture:
  toolBundle: fixtures/tools/timeout-once.json

expected:
  finalContains: ["配置", "总结"]
  maxSteps: 6
  requiredTools: ["read_file"]
  forbiddenTools: []

budgets:
  maxTotalTokens: 12000
  maxWallMs: 60000

judge:
  agentevals:
    mode: advisory
  deepeval:
    hardGate: true
```

### 7.2 统一 ScoreRecord

```json
{
  "caseId": "react.tool.timeout-recovery",
  "scorer": "deterministic.tool_recovery",
  "name": "tool_recovery_rate",
  "value": 1.0,
  "unit": "ratio",
  "pass": true,
  "hardGate": true,
  "status": "verified",
  "evidence": {
    "toolName": "read_file",
    "faultType": "timeout",
    "retryCount": 1,
    "toolInvocationIds": [11, 12]
  }
}
```

`status` 只能是：

- `verified`：证据完整，明确通过或失败。
- `unverified`：证据不足，不能默认通过。
- `not_applicable`：本 Case 不适用该指标。

### 7.3 Oracle 优先级

从强到弱：

1. 可执行状态：测试、DB diff、文件 hash、API 返回。
2. 环境事实：最终 URL、DOM、订单/报告/表是否真的存在。
3. 确定性文本规则：exact、must include、JSON schema、字段校验。
4. 固定 fixture 的轨迹匹配。
5. 校准后的 LLM Judge。
6. 人工评审。

能用强 Oracle 时，不使用 LLM Judge。

---

## 8. 第一阶段：评测基础框架

第一阶段严格承接前面确认的方案，先不依赖任何公开 Benchmark。目标是把“执行、采轨迹、评分、报告、门禁”打通。

### 8.1 阶段 1.1：Ledger 与 Trace

交付：

- `firstTokenAt` 全链路。
- 测试专用 Trace 导出 CLI。
- `trace.schema.json`。
- Trace 样例和契约测试。

验收：

- 首个内容/推理/工具增量只记录一次。
- 非流式为 `null`。
- 可导出 run / llmInvocations / toolInvocations / artifacts。
- 现有 Agent 行为和前端输出不变。

### 8.2 阶段 1.2：Java 测试执行入口

交付：

- `eval-runner` test profile。
- 独立测试库/schema。
- fixture 注入。
- 结果目录和明确退出码。
- Windows 原生调用脚本。

验收：

- 一个 smoke Case 可独立运行。
- 运行两次不会互相污染。
- 失败时能给出基础设施错误，而不是只给“Agent 失败”。
- 不新增生产 HTTP 接口。

### 8.3 阶段 1.3：Python 侧车骨架

交付：

- `evals/agent` 独立工程。
- `uv.lock`。
- `cute-evals` CLI。
- `CuteAgentSolver`。
- `TraceLoader`。
- Inspect AI Task。
- `EvaluationSuiteAdapter` 基础协议：内部 Case、公开 Benchmark 后续都实现同一入口。
- `Cute-Agent Bench` 命名空间和 manifest。

验收：

- `uv run cute-evals run --profile pr` 可调用 Java。
- 能生成 `trace.json`、`report.json`。
- 能注册一个内部 Suite，并列出其 Case。
- 外部 Benchmark Adapter 不需要修改 Runner 就能后续注册。
- Java 失败、超时、无 Trace 有明确错误。
- 不依赖 WSL。

### 8.4 阶段 1.4：10 个 ReAct Case

| 编号 | Case | 主要验证点 |
|---|---|---|
| R01 | 直接回答，不用工具 | 不应过度调用工具 |
| R02 | 单工具成功 | 工具选择、参数、结果汇总 |
| R03 | 两个工具串行依赖 | 依赖顺序和上下文传递 |
| R04 | 多工具结果汇总 | 多结果合并和完整性 |
| R05 | 空结果后降级 | 不伪造成功、明确降级 |
| R06 | 超时后重试成功 | 超时分类、重试、恢复 |
| R07 | 429 后重试成功 | 退避、重试语义 |
| R08 | 畸形 JSON | 解析失败与恢复 |
| R09 | 权限拒绝 / 工具不可用 | 明确失败、不死循环 |
| R10 | 错选工具后纠正或终止 | 错误选择、循环检测、停止条件 |

验收：

- 每个 Case 可独立重置。
- 故障只注入 fixture。
- 每个失败都能指向具体 step/tool/trace。
- PR 全部运行 ≤ 10 分钟。

### 8.5 子步骤 1.5：确定性 Scorer

指标：

| 指标 | 来源 | 门禁 |
|---|---|---|
| Task success | Run 终态 + Case Oracle | core hard |
| Tool success rate | ToolInvocation.status | core hard |
| Tool recovery rate | 故障注入后是否完成 | 按 Case |
| Unexpected tool count | 实际序列 vs 期望 | core hard |
| Max step violation | LLM invocation / step | core hard |
| Loop/no-progress | 重复工具+参数、轮次上限 | core hard |
| Total tokens | Ledger 汇总 | release hard |
| Wall time | run duration | release hard |
| First-token latency | `firstTokenAt - startedAt` | advisory |
| Redundant calls | 重复工具/参数 | advisory |

注意：

- 工具失败但 Agent 正确降级，应算恢复成功，不算工具成功。
- 工具成功率与故障恢复率必须分开。
- 首 token 缺失是 `unverified`，不是 0。
- 不用最终答案“看起来对”替代环境 Oracle。

### 8.6 阶段 1.6：DeepEval

交付：

- `DeepEvalScorer`。
- core Case 语义指标。
- 固定裁判模型、温度 0、缓存。
- `unverified` 处理。

建议指标：

- Task completion。
- Tool selection correctness。
- Tool argument correctness。
- Step efficiency（advisory）。

验收：

- 只有 `hardGate=true` 的 Case 因 DeepEval 失败阻塞。
- 裁判错误不会被记为通过。
- 相同 Trace 命中缓存。
- 报告区分 deterministic fail、judge fail、infra error。

### 8.7 阶段 1.7：agentevals

交付：

- `AgentEvalsTrajectoryScorer`。
- Trace -> agentevals trajectory 转换。
- 工具序列、参数、步骤摘要输入。
- 固定预期序列的确定性模式。
- LLM trajectory judge 的 advisory 模式。

验收：

- 对 R01-R10 能给出轨迹级分数和证据。
- 工具序列错误不会被最终答案掩盖。
- 轨迹 judge 失败标记 `unverified`。
- 首版不把不稳定的 LLM 轨迹 judge 作为 PR 唯一硬门禁。

### 8.8 阶段 1.8：RAG 评测

内部 RAG 不只看“回答像不像”，至少测：

- `recall@k`
- `MRR`
- context precision / recall
- faithfulness
- answer relevancy
- citation precision / coverage
- no-answer / 无证据时是否正确拒答
- retrieval latency、first token、total latency
- token / 单问题成本

集成：

- 现有 `cute-tool/cute_tool/tool/mrag/eval/runner.py` 通过 `MragScorer` 适配。
- Ragas 通过 `RagasScorer` 调用。
- 两套结果并列，不互相覆盖。
- 第一阶段 RAG 只 advisory，等人工标注集稳定后升级硬门禁。

验收：

- 能从私有 PDF/数据集回答并给出引用。
- 引用必须能定位到真实 chunk/page。
- 无证据时不能伪造引用。
- 召回分数与生成质量分开报告。

### 8.9 阶段 1.9：数据分析评测

内部数据分析 Case 至少覆盖：

- 单表聚合。
- 多表 join。
- 时间窗口趋势。
- 空结果和 NULL。
- 除零、类型转换、脏数据。
- 正确图表类型和关键数据点。
- 生成产物是否完整。

Oracle：

- 执行 SQL 后与黄金 SQL/黄金 DataFrame 比较。
- 数值允许明确容差，不允许模糊通过。
- artifact 检查文件存在、MIME、hash、表格行数、图表关键序列。
- 报告中的结论必须能追溯到查询结果。

验收：

- 失败能区分 SQL 错误、数据错误、推理错误、产物错误。
- 不把“生成了图表”当作分析正确。

### 8.10 阶段 1.10：报告与 CI

| 模式 | 模型 | 环境 | 重复 | 硬门禁 | 预算 |
|---|---|---|---|---|---|
| PR | 固定模型输出 fixture | 本地进程 + 独立测试库 | 1 次 | core 执行契约 + core DeepEval + stable trajectory | ≤ 10 分钟 |
| Nightly | live model | fixture/快照 + 独立测试库 | 3 次 | core + 稳定性 | ≤ 30 分钟 |
| Release | live model | Docker/VM + 独立数据库 + 完整工具 | 3–5 次 | core + regression + safety + 预算 | ≤ 60 分钟 |

统一命令：

```text
uv run cute-evals run --profile pr
uv run cute-evals run --profile nightly
uv run cute-evals run --profile release
```

报告必须包含：

- Case pass / fail / unverified。
- 失败分类：模型、工具、环境、超时、权限、持久化、Adapter。
- 完整 Trace 链接。
- 工具序列、重试、冗余调用。
- token、总耗时、首 token 延迟、成本。
- pass@1 / pass^k。
- baseline diff。
- DeepEval / agentevals / Ragas / MRAG 分量。

第一阶段总验收：

- 一个命令跑完 PR。
- 有一套完整、稳定的“考场 + 考生 + 考卷 + 监考记录 + 判卷老师 + 成绩单”框架。
- 有项目专属的 `Cute-Agent Bench` 初始题库，不冒充任何公开 Benchmark。
- 10 个 ReAct Case 全部独立。
- DeepEval、agentevals 和确定性 Scorer 都能输出统一 `ScoreRecord`。
- RAG 和数据分析各至少一组 smoke Case。
- 不依赖 WSL。
- 不新增生产评测接口。
- 新增普通 Case 不需要改 Runner。
- 新增领域 Scorer 不需要改 Java Runtime。
- 失败能定位到 Case、step、工具调用或 Trace。

---

## 9. 公开 Benchmark 的接入架构

### 9.0 接入边界

公开 Benchmark 的目录按只读资产处理：

- 上游 task、数据集、标签、工具 schema、环境和 evaluator 以固定 commit / digest 挂载。
- 本仓库只写 Adapter、manifest、启动脚本和测试。
- Adapter 只做协议转换，不修改题目、数据、工具定义或判分规则。
- 运行结束后不把修改后的 benchmark bundle 写回缓存。
- 无法无损接入时，结果记 `unverified` 或跳过；不得自行“修题”。

要改题、补题、换 Oracle、增加项目特例，就创建独立的 `Cute-Agent Bench` Suite，
使用独立 ID、manifest 和报告，不打着公开 Benchmark 的名义发布分数。

### 9.1 三种接入模式

#### 模式 A：单轮消息 + 最终答案

适用：

- GAIA。
- 自定义问答型 Benchmark。

```text
Benchmark task -> AgentAdapter -> trace.json -> official/custom scorer
```

#### 模式 B：临时工具 / MCP Shim

适用：

- tau²-bench。
- 工具选择型 Benchmark。

```text
Benchmark tools -> ephemeral MCP server
Agent tool calls -> Ledger trace
Ledger trace -> Benchmark function-call mapping
```

要求：

- 临时工具名、schema、返回值与 Benchmark 官方定义一致。
- 不改生产工具。
- 运行结束销毁临时 MCP。

#### 模式 C：环境在环

适用：

- Terminal-Bench。
- WebArena。
- OSWorld。
- SWE-bench。
- tau²-bench 的数据库最终状态。

```text
Benchmark environment (container/VM/browser/task server)
  -> Agent runs inside/against environment
  -> official oracle tests final state
  -> project Trace records execution facts
```

要求：

- 每个 task 从干净快照开始。
- 容器镜像 digest、网页数据、数据库快照 version 全部记录。
- 官方 evaluator 优先。
- 无法使用官方 evaluator 时，不发布官方分数，结果标为 `unverified` 或跳过。

### 9.2 BenchmarkAdapter 契约

```python
class BenchmarkAdapter(Protocol):
    name: str
    version: str
    upstream_commit: str

    def tasks(self, split: str) -> list[BenchmarkTask]: ...
    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask: ...
    def build_request(self, task: PreparedTask) -> AgentRequest: ...
    def collect(self, task: PreparedTask, run: AgentRun) -> BenchmarkRun: ...
    def score(self, task: PreparedTask, run: BenchmarkRun) -> TaskResult: ...
    def cleanup(self, task: PreparedTask) -> None: ...
```

BenchmarkAdapter 不允许：

- 直接读 Java 实体。
- 直接写产品数据库。
- 通过 SSE 判断任务结果。
- 修改生产工具。
- 把 Benchmark 私有字段塞进通用 Trace。

### 9.3 Benchmark manifest

每个 Benchmark 必须有：

```yaml
name: tau2
version: v1.0.1
upstream: https://github.com/sierra-research/tau2-bench
upstreamCommit: "<pinned-sha>"
license: "<checked-at-integration-time>"
split: official-subset
datasetHash: sha256:...
runnerMode: task-server
environment: task-server
officialEvaluator: true
scoreLabel: official-subset
taskIds:
  - "0"
budgets:
  maxWallMs: 120000
  maxTokens: 20000
```

`scoreLabel` 只能取：

- `official-full`：完整官方任务与官方 evaluator。
- `official-subset`：官方任务子集，官方 evaluator。
- `internal`：项目自建 Case。

公开 Benchmark 的数据、题目、标签、工具定义和 Oracle 一旦被修改，就不再属于上述
前两种标签，必须转为 `internal`，并放入 `Cute-Agent Bench`。

### 9.4 Benchmark 目录

```text
evals/agent/src/cute_evals/benchmarks/<name>/
├── adapter.py
├── manifest.yaml
├── oracle.py
├── tools/
└── tests/
```

数据集、镜像和上游 harness 放外部缓存：

```text
EVAL_DATA_HOME/
├── tau2/<commit>/
├── terminal-bench/<version>/
└── ...
```

仓库只保留 Adapter、manifest、patch 和测试，不把大体积受限数据集提交进 Git。

---

## 10. 公开 Benchmark 组合

### 10.1 优先级原则

1. 先接和产品核心能力最接近的 Benchmark。
2. 先接可执行 Oracle 强、环境成本低的。
3. 先接能产生明确修复动作的。
4. 高成本 Benchmark 只跑子集或 Nightly/Release。
5. 不用公开 Benchmark 调 prompt 后当作产品结论。

### 10.2 总览

| 优先级 | Benchmark | 主要测什么 | 接入模式 | 环境成本 | 首期范围 |
|---|---|---|---|---|---|
| P0 | 内部 ReAct Core | 真实 Agent 循环 | Java trace | 低 | 10 Case |
| P0 | 内部 MRAG | 私有 RAG | Trace + MRAG/Ragas | 中 | 固定标注集 |
| P1 | tau²-bench | 多轮工具 + 用户 + DB 终态 | 环境在环 | 中高 | 单 domain 子集 |
| P1 | GAIA | 通用多步任务 | 消息 + 工具/环境 | 中 | validation 小子集 |
| P1 | Spider 2.0 / BIRD | 数据分析 / SQL | 环境在环 | 中 | BIRD 子集优先 |
| P1 | Terminal-Bench | 终端多步任务 | 容器 | 高 | 子集 |
| P2 | WebArena / BrowserGym | 浏览器多步任务 | 浏览器环境 | 高 | 小规模子集 |
| P2 | Cute-Agent skill-sim | 本地技能选择、加载和恢复 | 消息 + Fixture | 低 | 5 类 Case |
| P2 | SWE-bench Lite | 代码修复 | 容器 + patch/test | 高 | Lite 子集 |
| P2 | AgentDojo | 提示注入、工具安全 | 环境在环 | 中高 | 安全子集 |
| P3 | OSWorld | 真机/VM 桌面操作 | VM + 截图 | 很高 | 极小 smoke |
| P3 | 全量 SWE-bench / WebArena | 完整能力上限 | 重型环境 | 很高 | Release 前 |

### 10.3 tau²-bench

目的：

- 多轮对话。
- 用户模拟器。
- 工具调用与环境状态变化。
- 最终数据库状态。
- `pass^k` 稳定性。

接入：

1. 起官方 task server / domain environment。
2. 用户模拟器独立运行。
3. 产品 Agent 通过 Adapter 接收用户消息并调用 domain tool。
4. 每个 task 重置数据库。
5. 官方 evaluator 检查最终 DB / tool state。

先做：

- 一个 domain 的 10–20 个 task。
- 固定用户模拟器模型和温度。
- 每个 task 重复 3 次。

指标：

- official reward。
- pass@1 / pass^k。
- DB final state correctness。
- turn count、tool calls、token、latency。
- 用户模拟器异常率。

退出条件：

- task 之间环境完全隔离。
- 不把“用户说完成了”当作成功。
- 支持中断、恢复和超时。

### 10.4 GAIA

目的：

- 通用多步任务。
- 文件、网页、推理和最终精确答案。

接入：

1. 取 validation 子集。
2. 下载附件并计算 hash。
3. Agent 在隔离 workspace 中运行。
4. 官方 exact-match / normalized answer validator。
5. 保留完整 Trace，不只保存最终答案。

指标：

- official exact match。
- 按 Level 1/2/3 分层。
- 附件读取成功率。
- 工具调用数、token、latency。
- 无答案 / 超时率。

退出条件：

- 附件和网页快照可复现。
- 不允许联网状态随意变化导致不可比较。

### 10.5 Spider 2.0 / BIRD

目的：

- 数据分析、schema understanding、SQL 生成、执行结果。

接入顺序：

1. [已完成] 先 BIRD 子集，SQLite 更容易隔离。
2. [已完成] 再接 Spider2-Lite 的官方本地 SQLite 子集；BigQuery / Snowflake
   方言暂不接入。
3. 产品使用 `sql_query` / data analysis 工具。
4. 官方 execution accuracy 优先。
5. 增加结果集 hash、SQL 合法性、时延和 token。

指标：

- execution accuracy。
- valid SQL rate。
- result exact / tolerance match。
- schema linking accuracy。
- query latency。
- token / query。

退出条件：

- 每个 task 数据库 snapshot 独立。
- 不只看 SQL 文本相似度。
- 结果数值有明确容差。

### 10.6 Terminal-Bench

目的：

- 终端多步任务、环境操作、命令执行、错误恢复。

接入：

1. 使用官方容器任务。
2. 产品 Agent 在容器内或对容器执行 shell 工具。
3. 官方 test script 作为 Oracle。
4. 每次 task 从干净镜像启动。

指标：

- official task success。
- timeout rate。
- command count。
- failed command recovery。
- token、wall time。

退出条件：

- 容器 reset 稳定。
- 不会把宿主机目录或密钥挂进容器。
- 有 CPU / 内存 / 时间上限。

### 10.7 WebArena / BrowserGym

目的：

- 浏览器多步任务。
- 页面导航、表单、检索、状态判断。

接入：

1. 起 WebArena 网站环境。
2. 使用产品 BrowserSkill / Playwright / Edge 自动化接口。
3. 每个 task 重置网站数据。
4. 官方 evaluator 检查 URL、DOM、数据库状态。

先做：

- 10–20 个 task。
- 单一网站或单一任务族。
- 固定浏览器版本、viewport、locale。

指标：

- official success。
- step count。
- element-not-found rate。
- navigation error rate。
- replay/live UI consistency（仅作为补充）。

退出条件：

- 网站数据可重置。
- 不依赖真实用户登录态。
- 浏览器异常和 Agent 决策错误能分开。

### 10.8 Cute-Agent Bench skill-sim

目的：

- Agent 是否会正确选择、加载并使用 skill。
- 在没有 Docker、官方镜像和官方 verifier 的条件下，仍能稳定判断 skill 轨迹。

接入：

1. 使用 Cute-Agent Bench 自有的 `skill-sim` Suite。
2. 通过 Java Fixture Runner 提供确定性 `skill_view` 动作和结果。
3. 真实执行 Java ReAct 循环、工具管线、Execution Ledger 和 Trace。
4. 用确定性 Oracle 检查 `skill_view(name=...)` 参数、加载次数、恢复和预算。

指标：

- task success。
- `skill_selection`：是否加载了需要的 skill，是否碰到禁止的 skill。
- `skill_load_count`：是否超过允许的加载次数。
- tool recovery：加载失败后是否成功恢复。
- step / token budget。

说明：

- 官方 SkillsBench 已退役，不再从 CLI 主路径运行，不启动 Docker，也不宣称官方成绩。
- `skill-sim` 是项目自有模拟评测，不能和 SkillsBench 官方成绩混写。

### 10.9 SWE-bench Lite

目的：

- 代码修复能力、shell/edit 工具、patch 产出、测试通过。

接入：

1. 每个 issue 一个容器。
2. 产品 Agent 读取仓库、修改文件、运行测试。
3. 从 workspace 提取 patch。
4. 官方 harness 应用 patch 并跑 FAIL_TO_PASS / PASS_TO_PASS。

指标：

- resolved rate。
- patch apply rate。
- test pass rate。
- command count、token、wall time。
- 上下文读取量。

先做：

- Lite 的 10–20 个 issue。
- Python 仓库优先。

退出条件：

- patch 与产品 workspace 严格隔离。
- 不允许把 test patch 泄漏给 Agent。

### 10.10 AgentDojo

目的：

- 提示注入。
- 恶意网页/文档。
- 工具误用。
- 越权、数据泄漏、危险动作。

接入：

1. 使用官方攻击任务和环境。
2. 给 Agent 提供受控工具。
3. Oracle 检查是否执行了禁止动作。
4. 同时检查最终答案和工具副作用。

指标：

- attack success rate。
- forbidden tool invocation rate。
- secret leak rate。
- unauthorized write rate。
- task utility（不能为了安全把正常任务全做废）。

建议：

- 在 Release 前必须接入至少一个安全子集。
- 安全 Case 的失败默认 hard gate，但要区分“环境没配好”和“真的越权”。

### 10.11 OSWorld

目的：

- 真机桌面、多软件、截图和键鼠操作。

接入：

1. 使用官方 VM / snapshot。
2. 产品 Agent 使用 CUA / 截图 / 键鼠工具。
3. 官方 evaluator 检查应用状态和文件。

策略：

- 只做极小 smoke。
- 只跑 Nightly 或 Release。
- 不把 OSWorld 作为 PR 门禁。

指标：

- official success。
- step count。
- screenshot latency。
- action failure rate。
- timeout / VM crash rate。

### 10.12 可选但暂不首期接入

| 项目 | 原因 | 何时考虑 |
|---|---|---|
| AgentBench | 覆盖广但任务差异大 | 需要横向能力地图时 |
| WorkArena | 企业软件型 Web 任务 | WebArena 稳定后 |
| MLE-bench | 机器学习工程任务，环境重 | 有代码/数据 Agent 需求时 |
| AutoGenBench | 更偏参考执行协议 | 不直接接入，吸收设计 |
| BrowserGym | 是环境/框架，不是单一榜 | 与 WebArena/WorkArena 一起用 |

---

## 11. 分阶段实施路线

下表的工时是相对规模，不是日历承诺；实际以依赖通过验收为准。

### 阶段 0：设计与冻结

状态：已完成。

交付：

- 本计划。
- Trace schema。
- Case schema。
- ScoreRecord / TaskResult schema。
- 门禁定义。
- Benchmark 接入规范。

退出条件：

- 用户确认。
- 不改业务代码。

### 阶段 1：评测基础框架

状态：已实现（2026-11-06）。PR 门禁已通过；Nightly / Release Live 入口已接线，
但仍需使用独立评测库完成真实模型验收。

包含第 8 节的 1.1–1.10。

规模：L（约 6–10 个工程周，单人）。

交付：

- `firstTokenAt`。
- Trace 导出 CLI。
- Python 侧车。
- 10 个 ReAct Case。
- 确定性 Scorer。
- DeepEval。
- agentevals。
- RAG smoke + MRAG + Ragas 适配。
- 数据分析 smoke。
- PR / Nightly / Release 报告与门禁。

退出条件：

- `uv run cute-evals run --profile pr` 可从零跑通。
- 所有评分结果都有统一 status / evidence。
- Trace schema 稳定。

### 阶段 1.5：真实轨迹回流与 Badcase 评测

优先级：高于公开 Benchmark。阶段一证明“评测系统能跑”，本阶段证明
“评测系统在面对真实用户任务分布时仍然有意义”。

当前实现状态（2026-11-06）：

- 已实现 `EvalTraceExportCli`，支持按 requestId、sessionId、时间窗只读导出。
- 已实现 Trace 脱敏器，覆盖密钥、PII、路径、内网/签名链接和 Artifact 链接投影。
- 已实现 `case-draft.yaml` 生成、`trace-replay`、`counterfactual-replay` 入口。
- 已新增 `private-badcase` Suite，并将真实 Case YAML 默认排除在 Git 之外。
- 已通过 Java 导出端口读取本地真实 Ledger，导出 19 条已完成 Run
  （17 条 SUCCESS、2 条 STOPPED），全部通过 Trace schema 校验。
- 已沉淀 5 个已确认私有 Badcase，覆盖模型决策、工具调用和浏览器/沙箱环境失败。
- `private-replay` 可从 Case 内的 `replay.baselineTrace` 一次回放全部 Badcase，
  连续两轮的状态、失败项和指标一致。
- 历史 LLM Invocation 的 `firstTokenAt` 为空，这是新增列上线前数据的预期行为。

数据来源：

- 只读 Execution Ledger 中已经完成的真实 Run。
- 不读取 SSE，不新增生产评测 HTTP 接口。
- 通过测试专用 / 运维专用只读 CLI 导出。
- Python 侧车不连接数据库、不读取 Ledger 表；数据访问只发生在 Java
  `ExecutionLedgerQueryService` 和 Java 导出端口内。

交付：

- `EvalTraceExportCli`：
  - 支持按 `runId`、`sessionId`、时间窗口导出。
  - 导出 Run、LLM Invocation、Tool Invocation、Artifact 和最终结果。
  - 输出稳定的 `trace.json`，复用阶段一的 Trace schema。
- Trace 脱敏器：
  - API key、Authorization、Cookie、Token、密码。
  - 用户 PII、手机号、邮箱、身份证、住址。
  - 内网地址、绝对路径和敏感下载链接。
  - Artifact 默认只保留类型、大小、hash 和相对引用，不复制生产文件。
- `case-draft.yaml` 生成器：
  - 从真实 Run 提取原始 Query、工具序列、失败点和可观察结果。
  - Oracle 默认留空，必须人工确认，禁止自动把“当时的输出”当正确答案。
- 三种回放模式：
  - `badcase-regression`：真实失败 Run 脱敏后固化为私有 Regression Case。
  - `trace-replay`：复用原 Trace 的输入、工具结果和环境快照，只重算评分。
  - `counterfactual-replay`：保持相同初始状态和用户输入，换模型重新执行并比较。
- 环境复现：
  - DB Snapshot / Fixture。
  - 文件与 Artifact 快照。
  - 工具目录、模型配置、Prompt 版本和随机种子。
- 私有数据边界：
  - 真实轨迹默认只进入私有 Regression Suite。
  - 未经人工批准，不进入 `Cute-Agent Bench`，更不进入公开 Benchmark。

首批规模：

- 5–10 个真实 Badcase。
- 至少覆盖 3 类失败：模型决策、工具调用、环境/持久化。
- 每个 Case 必须有脱敏 Trace、人工确认的 Oracle、稳定复现命令。

退出条件：

- 能从真实 Ledger Run 一键导出脱敏 Trace。
- 自动扫描不到密钥和明文 PII。
- 至少 5 个真实 Badcase 在本地稳定重放。
- 同一 Trace 的 replay 与评分结果可重复。
- Counterfactual Replay 能比较工具选择、恢复率、token、延迟和最终结果。
- 真实 Badcase 与自建 Case 在报告中明确分区，不混成一个成功率。

### 阶段 2：内部工具调用与故障注入

状态：已完成。

接入：

- 内部 `react-tools` 扩展集。
- 工具故障注入扩展。

规模：M（约 1–2 个工程周）。

交付：

- `BenchmarkAdapter` 基础接口。
- Java Fixture 与 Live Runner 的临时工具桥。
- `official-subset` 报告标签。

退出条件：

- 内部工具调用 Case 可重复跑。
- 工具调用轨迹和评分能对齐。
- 至少 3 类工具失败能进入报告。

完成记录：

- 内部工具调用与故障注入 Case 已进入 PR Suite，覆盖工具选择、参数和恢复。
- 报告新增工具故障事实，当前覆盖 `timeout`、`permission`、
  `malformed_payload`、`rate_limit`、`upstream`，恢复成功也不会隐藏故障。

### 阶段 2.5：评测实验内核强化

状态：已完成（2026-11-06），优先级 P0，先于阶段 3 开发。

目标不是继续增加 Benchmark 名字，而是把当前“能跑评测”提升为
“能稳定支撑代码变更决策的实验系统”。本阶段借鉴 Hermes evals 的 A/B、
隔离、续跑和统计纪律，但不复制它的 39 套独立目录结构。

规模：M（约 1–2 个工程周）。

#### 2.5.1 设计原则

1. 一次实验只改变一个变量。baseline 和 candidate 的模型、任务、Prompt、
   工具目录、seed、环境和重复次数必须一致，只允许被测代码或配置不同。
2. 执行事实优先。结果、工具调用、错误、token、耗时和产物必须来自
   Execution Ledger、环境状态或 artifact，不采信 Agent 自述。
3. 模型失败和基础设施失败分开。模型答错记 0 分；Adapter、数据库、进程或网络
   导致的失败单独标为 `infra_error`，不能伪装成模型失败。
4. 同一分母。baseline 和 candidate 使用完全相同的 case 集合、重复次数和
   统计公式；禁止只剔除某一臂的失败运行。
5. Cell 级隔离和恢复。每个 `(arm, model, case, rep)` 是独立 Cell，独立进程、
   独立 workspace、独立结果文件，失败后可安全续跑。
6. 公开 Benchmark 数据仍然只读。阶段 2.5 的陷阱题和 A/B Case 只进入
   `Cute-Agent Bench`，不得修改其他公开 Benchmark。
7. 不建立第二套评测平台。实验编排复用现有 `BenchmarkAdapter`、
   `EvalCase`、`ScoreRecord`、Execution Ledger 和统一报告。

#### 2.5.2 统一实验模型

新增版本化 schema：

- `experiment.schema.json`
- `cell.schema.json`
- `ab-report.schema.json`

`ExperimentSpec` 最少包含：

```yaml
schemaVersion: "1.0"
experimentId: tool-schema-ab-001
suite: tool-discovery
base:
  source: git
  ref: origin/main
candidate:
  source: git
  ref: HEAD
models:
  - group/deepseek
repetitions: 3
seed: 42
parallelism: 2
timeoutSeconds: 900
isolation:
  mode: process
  workspace: per-cell
  database: experiment
envFingerprint:
  javaCommit: ...
  pythonLockHash: ...
  datasetHash: ...
budgets:
  maxUsd: 20
  maxWallMinutes: 120
thresholds:
  maxAccuracyRegression: 0.05
  maxTokenRegressionRatio: 0.10
```

`CellKey` 必须由以下内容共同确定：

```text
experimentSpecHash + arm + model + caseId + repetition
```

`CellResult` 最少包含：

- `status`：`passed / failed / unverified / model_error /
  infra_error / adapter_error / oracle_error / skipped`。
- `taskScore`：0–1 部分分；同时保留 `hardGatePassed`。
- `tracePath`、`transcriptPath`、`oracleResults`、`artifactRefs`。
- `apiTurns`、`toolCalls`、`toolErrors`、`retries`、`totalTokens`、
  `wallMs`、`firstTokenMs`、`estimatedCost`。
- `failureOwnership`：模型、工具、环境、Adapter、Oracle 或未知。
- `retryHistory`、`startedAt`、`finishedAt`、`sourceFingerprint`。

结果状态不能只压在 `score` 一个字段里。必须同时保留“分数、硬门禁、
错误归属和是否进入准确率分母”四类事实。

#### 2.5.3 CLI 与实验生命周期

目标命令形态：

```text
uv run cute-evals ab run \
  --experiment experiments/tool-schema-ab.yaml \
  --out .eval-runs/ab/tool-schema-ab

uv run cute-evals ab resume --out .eval-runs/ab/tool-schema-ab
uv run cute-evals ab report --out .eval-runs/ab/tool-schema-ab
uv run cute-evals ab audit --cell <cell-key>
```

`ab run` 的顺序：

1. 校验 base / candidate 引用和 clean 状态。
2. 将两个版本解析到外部缓存中的独立 worktree，不在主工作区来回切换代码。
3. 编译 Java 和准备 Python 环境，记录产物 fingerprint。
4. 生成完整 Cell 清单。
5. 跳过已存在且 `status` 有效的 Cell。
6. 每个 Cell 启动独立子进程，执行、评分、原子写入结果。
7. 汇总报告，执行阈值门禁和异常审计。
8. 保留失败 Cell 的 Trace、transcript、标准输出和标准错误。

#### 2.5.4 Cell 隔离

PR / Fixture：

- 每 Cell 独立临时目录。
- 每 Cell 独立 requestId / sessionId / runId。
- 清空上一个 Cell 的 working memory、artifact、工具目录和模型缓存。
- 数据库使用 experiment 级 schema，并用唯一 run 前缀隔离；高风险 Case
  必须使用每 Cell 独立数据库快照。

Live / Nightly：

- 每 Cell 独立 Java 进程。
- 只注入允许的评测凭据，其他 API key、Cookie、Token 和宿主环境变量全部移除。
- 禁止访问真实用户 HOME、浏览器 profile、crontab、systemd user bus 和生产数据库。
- 必须提供 file-backed `crontab`、临时 HOME、禁用浏览器连接等宿主副作用
  shim；即使某工具当前被 eval profile 禁用，也要做负向测试。

Release / 高风险工具：

- 使用容器或独立数据库快照。
- 记录镜像 digest、数据库快照版本和网络 allowlist。
- 运行前后校验 workspace、数据库和 artifact 没有跨 Cell 污染。

#### 2.5.5 续跑与重试

- Cell 结果先写 `*.tmp`，再原子 rename 为正式 JSON。
- 已有且 spec fingerprint 一致的计分 Cell 不重复执行。
- Case、suite、模型、代码引用、Oracle 或环境 fingerprint 变化时，
  受影响 Cell 自动失效。
- `model_error` 记 0 分并保留在准确率分母，不自动重试。
- `infra_error / adapter_error` 不进入模型准确率分母，但必须出现在报告。
- 基础设施错误最多重试 2 次；baseline 和 candidate 使用相同重试规则。
- 连续 3 个 `infra_error` 触发实验级 fuse，停止整批并输出基础设施报告。

#### 2.5.6 统计规则

每个 Case 先计算多次重复后的 Case 均值，再对 Case 均值求实验总分，
禁止把所有运行直接混合成一个大分母。

最低规则：

- 默认重复 3 次。
- 同一 Cell 分数跨度大、两臂方向相反或结论接近阈值时，扩展到 6 次。
- 准确率分母 = 所有被评分模型运行 + 记 0 分的 `model_error`。
- `infra_error / adapter_error` 不计入模型准确率，但单列发生率。
- 效率指标只统计有效运行，并同时报告有效率。
- 至少报告 `mean task score`、`pass@1`、`pass^k`、token、turn、tool call、
  tool error、wall time 和成本。
- n=3 时只做描述性比较，不声称统计显著性。
- 任何低于 100% 的 Cell、方向反转或超预算运行必须进入 anomaly audit。

#### 2.5.7 首批陷阱任务族

只移植方法，不复制 Hermes 的任务数据。首批做 5 个内部任务族，每族
6–10 个 Case，并至少包含一个 control 和一个 distractor：

| 任务族 | 目的 | Oracle |
|---|---|---|
| hostile-files | 超大文件、单行巨文件、伪造扩展名、FIFO、空文件、近名文件 | 磁盘状态 + planted sentinel + trace |
| tool-discovery | 工具 schema 精简、deferred tool、错误工具纠正 | 官方或内部工具轨迹 Oracle |
| session-recall | 历史会话检索、分页、宽化查询、无关干扰 | 预置数据库 + 最终事实 |
| compaction-recall | 压缩前后召回率、恢复指针是否可用 | 金标问题 + 未压缩 control |
| clarify-multiturn | 歧义操作是否先确认、用户回复后能否继续 | workspace 终态 + scripted user replies |

额外规则：

- `clarify-multiturn` 若 Agent 用自然语言提问而不是结构化工具提问，测试器要发送
  scripted reply，并记录 `userRoundtrips`，不能因此直接判 0。
- 破坏性歧义 Case 的主门禁是“错误文件绝不能删除”，其次才是是否提问。
- `compaction-recall` 默认测试生产路径，即 summary + session_search recovery；
  同时保留 uncompacted control 作为召回上限。
- `tool-discovery` 必须包含 eager-only control 和 false-discovery distractor，
  区分“工具没被发现”和“模型本来就不用”。

#### 2.5.8 A/B 报告

阶段 2.5 报告必须回答四个问题：

1. candidate 是否在目标失败类型上真实改善。
2. 准确率是否退化，退化来自哪个任务和哪一类错误。
3. token / turn / tool call / wall time 的变化是否值得。
4. 基础设施错误、重试和异常运行是否影响结论。

报告结构：

```text
.eval-runs/ab/<experiment-id>/
├── experiment.json
├── cells/<cell-key>.json
├── traces/
├── transcripts/
├── logs/
├── report.json
├── report.html
└── audit.md
```

`report.json` 至少包含：

- base / candidate 两臂的 task × arm 表。
- 每格 n、均值、分数区间、pass 数、错误数和重试数。
- mean-of-task-means。
- token / turn / tool call / wall time / cost 的绝对值和比例变化。
- 每个任务的 win / loss / tie 和方向反转。
- `model_error / infra_error / adapter_error / oracle_error` 汇总。
- `ship / block / needs-more-runs / inconclusive` 机器建议。

最终发布结论仍由人工确认，`audit.md` 必须列出被抽查的异常 Cell 和证据链接。

#### 2.5.9 门禁

PR：

- 跑 A/B schema、resume、分母和隔离契约测试。
- 每臂跑 1–2 个 deterministic fixture Cell。
- ≤ 10 分钟。
- 不跑 live model。

Nightly：

- 至少 1 个强模型 + 1 个弱模型。
- 默认 3 次重复。
- 跑首批 5 个任务族的小子集。
- 输出 A/B 报告和 anomaly audit。
- 预算超限、连续 infra fuse 或硬门禁回退时告警。

Release：

- 跑选定任务族完整集。
- 争议 Cell 扩展至 6 次。
- 人工审计所有 sub-100% Cell、方向反转和高成本运行。
- 评测结论必须与发布说明绑定。

#### 2.5.10 交付物

- `experiment.schema.json`
- `cell.schema.json`
- `ab-report.schema.json`
- `ExperimentSpec / ArmSpec / CellResult / ABReport` 数据模型
- `cute-evals ab run / resume / report / audit`
- Cell 子进程编排器
- 临时 workspace、HOME、数据库和副作用 shim
- resume-safe 结果存储
- 同一分母和异常归属统计器
- 5 个内部任务族及 control / distractor
- A/B JSON / HTML 报告和 anomaly audit
- PR / Nightly / Release 门禁
- 使用文档和新增 Case 指南

#### 2.5.11 明确不做

- 不接 tau²、GAIA、BIRD、Terminal-Bench；它们仍属于阶段 3 以后。
- 不复制 Hermes 的 39 个目录或任务数据。
- 不建立每目录独立 runner / report。
- 不修改公开 Benchmark 的题目与 Oracle。
- 不把 LLM Judge 作为唯一 Oracle。
- 不在 PR 跑 live model 或重型浏览器 / VM。
- 不因某臂失败就单边剔除样本。

#### 2.5.12 退出条件

- 同一 experiment spec 可以稳定生成相同 Cell 清单。
- 强制杀掉运行后，`ab resume` 不重复已完成的 Cell。
- 模型错误记 0 分并保留分母；基础设施错误不计模型准确率但单列。
- baseline / candidate 的重试、分母和超时规则完全一致。
- 负向测试能证明 Cell 无法写真实 HOME、crontab 或生产数据库。
- 报告能直接指出哪个任务改善、哪个任务退化、哪个是噪声或基础设施问题。
- 首批 5 个任务族都能输出 0–1 分和硬门禁结果。
- PR A/B 契约门禁 ≤ 10 分钟。
- Nightly 报告包含 pass@1、pass^k、均值、tokens、turns、工具错误和成本。
- 公开 Benchmark 数据零修改。

### 阶段 3：多轮交互与技能

状态：框架已落地（2026-11-06）；tau² 官方子集已完成真实夜间首跑，GAIA gated access
已打通并完成 Level 1 两题 live 子集验收，但尚未跑完整 validation；官方 SkillsBench
已退役，技能评测改为本地 `skill-sim`。
多轮 Trace、用户模拟器、task server、环境快照、官方 evaluator Adapter、
skill-sim 轨迹评分和官方子集配置已进入 `evals/agent`；PR 仍只跑 deterministic fixture。

已验证：

- tau² `airline` 官方 task `0 / 1 / 3` 通过 Java 多轮会话真实执行；
  官方 `UserSimulator`、官方 domain tool、官方 evaluator 全链路生效。
  首轮 3/3 通过；随后 2 次重复的真实报告为 `pass@1=2/3`、`pass^k=2/3`，
  task `0` 的一次运行因模型违规取消而被官方 DB 终态 Oracle 判 0，
  说明评测能识别“表面完成、实际违反政策”的结果，不能只看最终回答。
- task server 的 stderr 已由后台线程持续 drain；否则长时间 tau² 会话会写满管道，
  把已成功执行的工具调用误报成 60 秒超时。
- GAIA Level 1 官方两题完成 live 子集：`2/2` 通过，`pass@1=100%`、`pass^k=100%`。
  答案契约固定为 `gaia-final-answer-v1`：官方题目和 `question_scorer` 不改，
  Adapter 只把最后一个 `FINAL_ANSWER:` 后的文本交给官方 scorer，报告保留
  `rawAnswer` / `finalAnswer` 审计证据。
- GAIA 两题分别消耗 34,647 / 9,517 tokens，耗时 16.879 / 2.639 秒；结果只标记
  `official-subset`，不能写成完整 GAIA validation 成绩。
- Java Live Runner 已把 `maxWallMs`、`maxTotalTokens`、`maxToolCalls` 纳入单题预算；
  2026-11-07 起改为 run 级共享 `RunBudget`：主 Agent、子 Agent 与并发 fork 使用同一
  Token/工具额度，工具执行前原子扣减，超出后不再产生工具副作用。
- 预算在 80% 进入收尾区：停止探索，预留最后一个步骤和约 20% 预算，强制用已有证据
  生成 `FINAL_ANSWER`。软收尾成功但仍超硬上限时仍记 SLO 失败，不能假通过。
- 报告已拆分 `qualityPassAt1`（答案和硬门禁质量）与 `sloPassAt1`（是否在预算内完成）。
  `max_steps`、`token_budget`、`wall_time` 只进入 SLO 口径，不污染答案质量；“答案正确但
  预算超限”会显示为质量通过、SLO 失败，不再被误写成答案答错。
- 共享预算下每个 Agent 可各自领取一次强制收尾，避免子 Agent 先触发 80% 收尾后，主 Agent
  因全局收尾锁而继续空转。
- WebFetch 增加网络故障分类与 run 级断路：同一 URL 最多抓 2 次；连续 2 次代理/连接
  故障后，本轮后续 WebFetch 直接快速失败并提示换源或收尾。
- SkillsBench 官方评测不再使用 Docker 或官方 verifier；改为 Cute-Agent Bench
  自有的 `skill-sim`，覆盖正确选择、干扰项、失败恢复、无 skill 控制和加载预算。

尚未宣称通过：

- GAIA validation 完整集尚未运行；当前只完成官方 Level 1 两题，不能把子集结果
  写成完整官方成绩。无附件证据时仍必须 `unverified`，不能算通过。
- `skill-sim` 的 PR 结果只证明本地 Fixture 和 Oracle 契约；真实模型能力仍需要在
  独立 live 环境验证。
- 预算 watchdog 仍保留为 wall-clock 兜底，但 Token 与工具调用已改为运行内前置门禁；
  子 Agent 启动前也会检查父 run 取消状态，避免取消后继续创建新子任务。

#### GAIA 全量评测延后登记（2026-11-06）

状态：**延后，不在当前开发阶段执行。**

- 当前范围只到官方 `official-subset`，GAIA Level 1 两题已验收。
- 全量 validation 需要先把 Adapter 从固定 2/2/1 子集扩展到完整官方 selection，
  并补齐全量附件下载、工具覆盖、Case 隔离、预算和续跑策略。
- 全量题目数量和运行时间以固定 revision 的实际数据为准；当前按约 165 题估算。
- 单次全量预估 1.5–5 小时正常、6–10 小时偏慢，最坏受每题 300 秒上限约束约 13.75 小时。
- 单次 token 预估正常约 500 万–800 万，容量预留按 1000 万；理论上限约 1320 万，
  重复 3 次时理论上限约 3960 万。
- 完成条件：完整官方 validation 题目、官方 evaluator、`official-full` 标签、
  全量附件 hash、完整 Trace、预算失败分类和可复核报告全部具备后，才可执行并宣称成绩。

接入：

- tau²-bench 单 domain 子集。
- Cute-Agent Bench skill-sim 本地 Suite。
- GAIA validation 子集（当前只跑 `official-subset`；全量延后）。

规模：M–L（约 2–4 个工程周）。

交付：

- 用户模拟器 Adapter。
- task server Adapter。
- 环境重置与 DB 终态 Oracle。
- skill 选择、加载次数和恢复 Scorer。
- 附件/网页快照管理。

退出条件：

- 每个 task 完全重置。
- `pass^k` 能计算。
- 用户模拟器与 Agent 的 trace 能关联。

### 阶段 4：RAG 与数据分析公开评测

启动状态（2026-11-06）：**4A、4B、4C、4D 已实现。** 完整 GAIA 已登记为延后项，
不影响阶段 4；`data-core` 已扩展为 6 个项目自有数据 Case，BIRD 2026 Mini-Dev
SQLite 官方子集和 Spider2-Lite 官方 SQLite 子集均已接入。

启动顺序：

1. **4A 内部 MRAG 完整标注集**：先复用现有 MRAG 评测，锁定数据集 hash、
   文档 chunk/page 引用和标注版本；RAG 的召回、引用覆盖、faithfulness 分开报告。
2. **4B 数据分析内部基准集**：增加独立 SQLite/数据集快照，执行 SQL 后用结果集
   Oracle 判断，不以 SQL 文本相似度或 Agent 自述作为成功依据。
3. **4C BIRD 子集**：固定官方数据 revision、数据库 snapshot 和官方 execution
   evaluator；只标 `official-subset`，不修改题目、数据库和标签。
4. **4D Spider 2.0 子集**：在 BIRD 的结果集比较、方言和隔离逻辑稳定后再接。

4A 已完成内容：

- `cases/rag/core/` 增加 7 个项目自有 MRAG 标注 Case，覆盖直接页面、多来源、
  版本冲突、表格阈值、图片证据、时间条款和无证据拒答。
- `cases/rag/dataset.json` 固定数据集 revision、标签和 case-file SHA-256；
  `rag_dataset.py` 在运行 `rag-core` 前校验，Case 文件改动会直接拒绝运行。
- `citation_recall` 负责检索层，`citation_coverage` 负责最终引用层，
  Ragas `faithfulness` 负责生成层；无证据 Case 不再伪造 recall 失败。
- deterministic fixture：`7/7` 通过，`pass@1=100%`、`pass^k=100%`。
- 配置本地 Judge 并启用 `--require-judge` 后：`7/7` 通过，三层指标均 `verified`。
- 当前报告示例：`D:\cute-eval-tmp\rag-core-v5\report.html`。

4B 已完成内容：

- `cases/data/core/` 扩展为 6 个项目自有 Case，覆盖单表计算、联表聚合、
  时间窗口、空结果、NULL 分类和数值聚合。
- SQLite fixture 固定在 `cases/data/core/fixtures/analytics.sql`，数据集定义和
  Case/fixture SHA-256 固定在 `cases/data/dataset.json`，运行前由
  `data_dataset.py` 校验。
- SQL Oracle 在内存 SQLite 中导入 fixture，只允许单条只读 `SELECT` / `WITH`，
  开启 `PRAGMA query_only=ON`，使用 SQLite authorizer 拒绝写操作，并比较列名和
  结果集，支持数值容差和有序/无序比较。
- `sql_execution` 负责执行事实，`sql_result` 负责数据结果；失败分类为
  `data_oracle`，不能用 SQL 文本相似度或 Agent 自述替代结果判定。
- deterministic fixture：`6/6` 通过，`pass@1=100%`、`pass^k=100%`。
- 本地 Judge + `--require-judge`：`6/6` 通过。
- 报告示例：`D:\cute-eval-tmp\data-core-v4\report.html` 和
  `D:\cute-eval-tmp\data-core-v5-judge\report.html`。

4C 已完成内容：

- 固定 BIRD 2026 Mini-Dev SQLite 数据 revision
  `5b256b5a9c17478f06ed6912612e9a14d17065c8`、官方代码 commit
  `1c49a375e76c471e218564da3f4617513d33e371`、数据文件 SHA-256、
  官方 evaluator 文件 SHA-256。
- 首轮子集固定为 `0, 1, 49, 50, 149, 194, 195, 196`，覆盖
  `california_schools`、`card_games`、`debit_card_specializing`、`financial`
  四个官方数据库，以及 simple / moderate / challenging 三种难度。
- 不启动 Docker daemon；直接从官方 OCI 镜像读取 `seed.sqlite` layer，同时校验
  镜像 digest、layer SHA-256 和数据库文件 SHA-256。每个 task 使用独立只读快照。
- Java Agent 只获得 `list_tables`、`describe_table`、`sql_query` 三个临时工具；
  SQL runtime 使用只读 URI、`PRAGMA query_only=ON` 和 SQLite authorizer。
- 增加 `submit_sql` 和 `bird-final-sql-v1` 提交契约。契约只规定最终 SQL 如何提交，
  不修改官方题目、evidence、gold SQL、数据库或 Oracle。
- Execution Ledger 中的最终 SQL 交给固定版本官方 `calculate_ex`；报告同时记录
  SQL 合法性、调用次数、执行时延、结果 hash、tool 成功率、token 和 wall time。
- gold-to-gold 官方 EX 自检：`8/8` verified。
- live 子集首轮：`6/8`，`pass@1=75%`，`pass^k=75%`；task `0` 官方结果集不一致，
  task `49` 未调用 `submit_sql`。
- live 子集 `repeat=3`：`pass@1=87.5%`，`pass^k=75%`；6 题稳定通过，
  task `0` 稳定失败，task `49` 不稳定。task `49` 第 3 次官方 EX 正确，
  但消耗 `97,263 tokens`、21 次 tool call，超过预算硬门禁。
- 首轮报告：`D:\cute-eval-tmp\bird-live-8\report.html`
- 三次重复报告：`D:\cute-eval-tmp\bird-live-8-r3\report.html`
- 数据集 hash：
  `sha256:bf49e8e5e9e25256779b0e2da594979200be2797af4cb9a0f83d172ac4edbf44`

4C 只标记为 `official-subset`，不是 BIRD Mini-Dev 500 题全量成绩。

4D 已完成内容：

- 固定 Spider2 官方代码 commit
  `cafb867313aab4e674652054198f383cf4018943`、`spider2-lite.jsonl` SHA-256、
  官方 evaluator、gold 配置、gold SQL、gold CSV 和 SQLite 数据库包 SHA-256。
- 只接 Spider2-Lite 的官方本地 SQLite 子集，不接需要 BigQuery / Snowflake
  凭据的题；公开题目、gold、数据库和 oracle 均未修改。
- 首轮固定 8 题：`local004, local008, local019, local022, local039, local058,
  local078, local199`，覆盖 `E_commerce`、`Baseball`、`WWE`、`IPL`、`Pagila`、
  `education_business`、`bank_sales_trading`、`sqlite-sakila` 八个官方数据库。
- 选择规则：只从有官方 gold SQL 和 gold CSV 的 `local*` 题中选，先做
  gold-to-gold 自检；不能复现官方 CSV 的候选直接淘汰，不通过改题或改 Oracle
  凑通过率。
- Adapter 校验数据、evaluator、gold SQL、gold CSV 和每个数据库快照的 SHA-256；
  task 使用独立只读数据库副本，Java Agent 只获得 `list_tables`、
  `describe_table`、`sql_query`、`submit_sql`。
- 官方 `evaluate.py` 顶层依赖 BigQuery，不能整文件 import。Adapter 在 hash
  校验后只加载官方 `extract_sql_query`、`compare_pandas_table`、
  `compare_multi_pandas_table`，保留官方 `condition_cols`、`ignore_order` 和
  `1e-2` 容差。
- 带 `external_knowledge` 的官方题如果文档不可用，官方硬门禁记为 `unverified`；
  当前 8 题均不依赖外部知识。
- gold-to-gold 官方结果集自检：`8/8` verified。
- live `repeat=1`：`7/8`，`pass@1=87.5%`，`pass^k=87.5%`，`unverified=0`。
  task `local019` 官方结果集正确，但消耗 `68,762 tokens / 20 tool calls`，
  超过 `60,000 tokens` 预算并同时到达工具调用上限，因此整体判失败。
- 报告：`D:\cute-eval-tmp\spider2-live-8\report.html`
- 数据集 hash：
  `sha256:37a8aea86736d01609bf5b420f2a01f6e84f89f8a6f17ce363ee759535cb9a32`

4D 只标记为 `official-subset`，不是 Spider2-Lite 547 题或 135 道 SQLite 题
全量成绩。

阶段 4 的 PR 只跑 deterministic fixture 和内部固定 Oracle；Nightly/Release
才跑真实模型与公开 Benchmark 子集。

接入：

- 内部 MRAG 完整标注集。
- Ragas 指标。
- [已完成] BIRD 2026 Mini-Dev SQLite 官方子集。
- [已完成] Spider2-Lite 官方 SQLite 子集。

规模：L（约 2–4 个工程周）。

交付：

- RAG 基准集。
- SQL/数据基准集。
- execution accuracy。
- citation / faithfulness 报告。
- artifact 数据检查。

退出条件：

- RAG 的召回、引用、生成分三层报告。
- SQL 以执行结果为准。
- 数据和产物可复现。

### 阶段 5：终端与编码 Benchmark

接入：

- Terminal-Bench 子集。
- SWE-bench Lite 子集。

规模：L–XL（约 3–6 个工程周）。

交付：

- Docker 环境 Adapter。
- 容器 reset。
- patch 提取。
- 官方 test script / harness。
- 资源与超时限制。

退出条件：

- 测试隔离，不污染宿主机。
- PR 不跑重型任务。
- 失败可分类为环境、工具、patch、test、模型。

### 阶段 6：浏览器与安全 Benchmark

接入：

- WebArena / BrowserGym 子集。
- AgentDojo 安全子集。

规模：XL（约 3–6 个工程周）。

交付：

- 网站快照 / 数据重置。
- 浏览器环境 Adapter。
- DOM/URL/DB evaluator。
- 提示注入和越权 Scorer。
- 安全失败分级。

退出条件：

- 不依赖真实用户登录态。
- 每次任务可重置。
- 安全失败有 trace 证据。

### 阶段 7：重型与长期能力

接入：

- OSWorld smoke。
- 全量 SWE-bench / WebArena 选跑。
- 其他 WorkArena / MLE-bench（按产品方向）。

规模：XL+。

交付：

- VM/snapshot 管理。
- Nightly / Release 长任务调度。
- 预算和并发控制。
- 人工复核队列。

退出条件：

- 成本可控。
- 不影响 PR。
- 失败能自动归因和生成复查包。

### 阶段 8：持续运营与改进闭环

启动状态（2026-11-06）：**第一版已实现，线上 Trace 自动采样与 Python 每日调度已实现。**
已完成 append-only 评测历史、趋势报告、A/B 矩阵汇总、release gate、Badcase 人工审核入库、
Execution Ledger 只读导出、问题/健康对照分层采样和健康审计报告。可选观测平台尚未接入。

交付：

- [已完成] baseline 趋势。
- [已完成] 模型 / prompt / 工具矩阵汇总。
- [已完成] release gate 与版本、commit、模型、数据集 hash 关联。
- [已完成] Badcase 人工确认后进入 `private-badcase` regression，默认 hard gate。
- [已完成] Java 导出端支持状态过滤、时间窗口 keyset 分页，仍只读并在落盘前脱敏。
- [已完成] Python `ops sample` 确定性抽样问题 Trace 和健康对照组，生成审计报告及 Badcase 草稿。
- [已完成] Python 常驻调度命令 `ops schedule`；默认关闭，显式 `--enable` 才启动，固定时刻触发、
  串行执行、失败写状态、单进程运行。
- [未承诺] 自动 Oracle / 自动审批：线上 Trace 的任务正确性不能凭健康指标推断，必须人工确认 Oracle。
- 可选 Langfuse / Opik / Phoenix 观测。

第一版实现细节：

- 新增 `ops.py`，以 append-only JSONL 保存评测历史，不引入数据库。
- `ops record` 记录 suite、variant、profile、dataset hash、git commit、模型环境、
  pass 指标、token / latency 中位数、失败分类和逐 Case 指标；同一报告重复记录幂等。
- `ops trend` 按 `series + variant` 比较历史，识别 Case regression / recovery、
  pass 下降、token 上涨和延迟上涨，并生成 `trend.json` / `trend.html`。
- `ops matrix` 汇总多个既有 A/B `report.json`，展示 base/candidate、
  `pass@1` / `pass^k` delta、token ratio、异常和 recommendation。
- `ops release` 检查 failed、unstable、unverified、baseline 存在性、baseline
  regression 和最低 `pass^k`，生成 `release-record.json` / `.md`；失败时返回非零。
- `case-approve` 要求 `replay.oracleReviewed=true` 且至少存在一个确定性 Oracle，
  记录 reviewer、审批时间和备注；默认将 Case 设为 hard gate，`--advisory` 可降级。
- `ops sample` 经 Java `ledger-export` 读取 `[from,to)` 窗口，按终态过滤并采用 keyset 游标分页，
  扫描范围最多 500 条；Python 按风险优先挑问题 Trace，再用 seed 确定性抽取健康对照。
- 健康审计仅报告终态、空终答、工具错误、预算标记、重复调用、Token、延迟等可观测事实；
  不把这些健康信号包装成任务正确率。
- 线上问题 Trace 只生成 `expected: {}`、`oracleReviewed: false` 的候选草稿；
  没有自动审批、没有自动写入私有回归目录。
- 每日任务由 Python 长驻进程维护，不依赖 Windows Task Scheduler。启动时校验显式 `CUTE_EVAL_DB_*`
  环境变量；`schedule.enabled` 默认 `false`，不加 `--enable` 时只打印关闭状态并退出，不启动常驻进程。
  每次运行串行执行，不重叠。状态写入 `scheduler-state.json`，失败保留错误并等待下一周期。
  保留清理只删除带本工具 schema 标记且过期的运行目录。
- 固定边界：没有人工确认不能进入 regression；release record 不代表自动部署；
  运营历史和私有 Badcase 不进入公开 Benchmark。

运营 smoke：

- 3 个已有 A/B 实验成功汇总为矩阵。
- Spider2 `7/8` 报告被 release gate 判为 `block`；PR fixture 完整 6 项检查判为
  `ship`。
- 报告示例：`D:\cute-eval-tmp\ops-demo\trend\trend.html`、
  `D:\cute-eval-tmp\ops-demo\matrix\matrix.html`、
  `D:\cute-eval-tmp\ops-demo\release-spider2\release-record.json`。

退出条件：

- 每个线上 badcase 都能固化为 Case。
- 每个版本都有可比较基线。
- 评测不是一次性项目，而是持续门禁。

---

## 12. 指标矩阵

### 12.1 ReAct / 工具

- task success。
- tool selection accuracy。
- parameter correctness。
- tool invocation success rate。
- recovery rate。
- redundant call count。
- step count。
- loop rate。
- pass@1 / pass^k。
- total tokens。
- wall time。
- first token latency。

### 12.2 RAG

- recall@k。
- MRR。
- context precision / recall。
- faithfulness。
- answer relevancy。
- citation precision / coverage。
- no-answer accuracy。
- retrieval latency / total latency。
- token cost。

### 12.3 数据分析

- SQL valid rate。
- execution accuracy。
- result exact / tolerance match。
- schema linking accuracy。
- chart/artifact correctness。
- insight groundedness。
- query latency。
- token cost。

### 12.4 公开 Benchmark

每个 Benchmark 必须同时报告两类指标：

**官方指标**：

- 只使用官方 evaluator；使用子集时明确标注 `official-subset`。
- 官方 evaluator 不可用时，不发布官方指标，标记 `unverified`。

**运营指标**：

- token。
- wall time。
- tool calls。
- error categories。
- pass^k。
- 单任务成本。

这样才能回答“能不能上榜”和“上线是否划算”，而不是只看一个分数。

---

## 13. CI 门禁设计

### 13.1 PR

- 只跑 deterministic core。
- 固定模型输出 fixture。
- 真实执行 Java Agent 控制流、工具 fixture、Ledger、Trace。
- 1 次运行。
- ≤ 10 分钟。
- 不跑 Docker 重型 Benchmark。
- 失败必须可定位。

### 13.2 Nightly

- 跑 live model。
- 外部环境仍用 fixture / snapshot。
- core Case 重复 3 次。
- 跑 GAIA、tau² 的小子集。
- 计算 pass@1、pass^k、token、延迟。
- 与 baseline diff。
- advisory 为主，稳定后再硬卡。

### 13.3 Release

- 跑完整 core + regression + safety。
- 跑 Docker/VM/Browser Benchmark 子集。
- 跑 live model 3–5 次。
- 检查预算、稳定性、安全。
- 人工复核失败和高风险 Case。

### 13.4 不允许

- 没跑就说通过。
- 外部 API 失败当 Agent 失败。
- Adapter 失败当模型失败。
- `unverified` 默认通过。
- 没有 baseline 就制造回归。

---

## 14. 报告设计

### 14.1 机器报告

`report.json` 是唯一机器可读真源：

```json
{
  "runId": "eval-...",
  "profile": "pr",
  "agent": "cute-agent",
  "model": "fixed-model",
  "suite": "react-core",
  "caseResults": [],
  "scores": [],
  "summary": {
    "passed": 0,
    "failed": 0,
    "unverified": 0
  }
}
```

### 14.2 人看报告

`report.html` 至少包含：

- 总览和通过率。
- Case 列表和状态。
- ReAct 时间线。
- 工具调用表。
- token / 耗时 / 首 token。
- 失败证据。
- Trace / artifact 链接。
- baseline diff。
- Benchmark 标签：内部 / 官方全量 / 官方子集 / unverified。

### 14.3 结果目录

```text
.eval-runs/<run-id>/
├── trace.json
├── result.json
├── report.json
├── report.html
├── traces/
├── artifacts/
└── logs/
```

所有报告路径使用相对路径，避免泄漏宿主机绝对路径。

---

## 15. 可复现与安全

### 15.1 必须锁定

- Java git commit。
- Python `uv.lock`。
- Inspect AI / DeepEval / agentevals / Ragas 版本。
- 模型 ID 和版本。
- Judge prompt 版本。
- Case 版本。
- 数据集 SHA256。
- Benchmark upstream commit。
- 容器镜像 digest。
- 浏览器版本和 viewport。
- 随机种子。
- 预算（token / 时间 / 工具次数）。

### 15.2 隔离

- PR：独立测试库 / schema。
- Nightly：独立数据库快照。
- Release：容器或 VM。
- 所有 Benchmark 数据使用临时 workspace。
- 运行结束清理。
- 不把真实 Cookie、Token、密钥写入 Trace。

### 15.3 安全

- Trace 导出前脱敏。
- 工具参数中的密钥、Authorization、Cookie 必须过滤。
- Benchmark 恶意输入不能进入生产日志。
- AgentDojo 类安全 Case 使用专用 workspace。
- 报告不包含宿主机绝对路径。

---

## 16. Agent 命名同步与单一评测入口

Cute Agent 使用统一的名称、Java Runtime 和 Execution Ledger 契约，
不建立第二套评测系统。

同步规则：

1. 当前评测、Trace、Report、Baseline 统一使用 `cute-agent` 标识。
2. CLI 只保留单一运行入口，不提供 Agent 切换参数。
3. 不为旧名新增独立 Adapter、Scorer、报告或跨 Agent 对比。
4. 如果产品最终再次改名，只做一次名称、路径、配置和报告标识的集中同步。

验收：

- 仓库评测文档不再把旧名描述为第二个 Agent。
- CLI 不存在 Reactor 评测入口。
- Case、Scorer、Report、Benchmark Adapter 只有一套。
- 缺字段时明确 `unverified`，不补造。

---

## 17. 主要风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| Trace schema 不稳定 | 所有适配器反复返工 | 第一阶段冻结 schema，版本化，先做契约测试 |
| Java/Python 边界泄漏 | Python 依赖内部实现 | 只依赖 JSON schema 和进程退出码 |
| DeepEval 波动 | PR 误挂、成本高 | 固定模型、温度 0、缓存、只卡 core、unverified |
| agentevals 不稳定 | 轨迹 judge 误判 | 先 advisory，确定性序列硬门禁，人工校准 |
| Benchmark 环境不重置 | Case 污染 | 每 task snapshot/reset，运行前后校验 |
| 公开 Benchmark 过拟合 | 分数虚高 | 私有 holdout + 内部 Case + 不公开调 prompt + `ops overfit` 泛化审计 |
| 多个框架抢主入口 | 工程失控 | Inspect AI 单 Runner，其他只做库/Scorer |
| 重型环境成本高 | CI 慢、贵、易崩 | PR 不跑，Nightly 子集，Release 全量 |
| Adapter 错归因 | 错怪模型 | 基础设施失败和 Agent 失败分开分类 |
| 数据/密钥泄漏 | 合规风险 | Trace 脱敏、临时 workspace、报告相对路径 |
| 官方 evaluator 版本漂移 | 结果不可比 | 固定 commit、记录 digest、报告标注版本 |
| 只测最终答案 | 假成功 | 环境 Oracle 优先，轨迹作为补充 |
| A/B Cell 污染 | 两臂结果被前一个 Case 的 workspace、数据库或缓存影响 | 每 Cell 独立进程、workspace、数据库前缀/快照，并做运行前后污染校验 |
| 同分母被破坏 | 通过剔除失败运行虚增某一臂准确率 | Cell 清单先冻结，重试规则一致，`model_error` 留在分母，报告展示被排除的 `infra_error` |
| 续跑重复执行 | 重复消耗预算、覆盖率失真或混合旧结果 | 以 `experimentSpecHash + arm + model + caseId + repetition` 作为 CellKey，原子写结果，fingerprint 变化自动失效 |
| 宿主副作用泄漏 | 评测误写真实 HOME、crontab、浏览器 profile 或生产数据库 | 临时 HOME、file-backed crontab、禁用真实连接、负向测试和容器/快照隔离 |

---

## 18. 最终完成标准

### 第一阶段完成

状态：已完成。

```text
uv run cute-evals run --profile pr
```

能稳定输出：

- 10 个 ReAct Case。
- RAG 与数据分析各 1 个 smoke Case。
- core DeepEval。
- agentevals 轨迹分。
- RAG smoke。
- 数据分析 smoke。
- `report.json` / `report.html`。
- baseline diff。
- 本地 Judge `group/deepseek` 实测 12/12 通过，DeepEval / agentevals / Ragas 均为 `verified`。

Live 模型入口、独立评测库和 `eval` Profile 已接线；真实模型的 Nightly / Release
验收属于下一轮环境联调，不影响阶段一的 PR 退出条件。

### 阶段 1.5 完成

状态：已完成。

- 真实 Execution Ledger Run 可只读导出。
- Trace 脱敏和安全扫描通过。
- 5 个已确认真实 Badcase 进入私有 Regression Suite。
- Replay / Counterfactual Replay 可稳定执行。
- 真实 Case 和自建 Case 在报告中分区。
- 每个真实 Badcase 都有可追溯的 Trace、Oracle 和复现说明。

### 阶段 2.5 完成

状态：已完成（2026-11-06）。

- 实验 spec、Cell 和 A/B 报告 schema 冻结并有契约测试。
- `ab run / resume / report / audit` 可复现、可续跑、可审计。
- baseline / candidate 使用同一任务、模型、seed、重复次数、超时和重试规则。
- `model_error`、`infra_error`、`adapter_error`、`oracle_error` 分开统计，
  且同分母规则有负向测试。
- 首批 5 个内部任务族能输出部分分、硬门禁和异常审计。
- PR 只跑 deterministic A/B 契约；Nightly 输出 `pass@1 / pass^k`；
  Release 对争议 Cell 扩展到 6 次并人工复核。
- 报告能回答“改善了什么、退化了什么、是否值得、是否被基础设施污染”。

阶段 2.5 已实现并验收：

- 新增 `experiment / cell / ab-report` 三份 schema 和 A/B 数据模型。
- 新增 `cute-evals ab run / resume / report / audit`。
- Cell 使用独立子进程、临时 HOME、file-backed crontab 目录、浏览器禁用标记、
  独立 Java 临时目录和 experiment / cell run 前缀。
- Cell 结果原子写入，按 spec 与 case fingerprint 安全续跑；基础设施错误可重试，
  连续基础设施错误触发 Fuse。
- 新增 `cases/ab/` 五个任务族，每族 6 个 Case，共 30 个内部 Case。
- 真实 deterministic 双 Arm smoke：30 个 Case × 2 Arm = 60/60 Cell 通过；
  `ab resume` 不重复执行已完成 Cell。
- PR 工作流新增 2 Cell deterministic A/B contract smoke。

### 中期完成

- tau²、GAIA、BIRD/Spider、Terminal-Bench 至少各有稳定子集。
- 每个 Benchmark 有 manifest、adapter、oracle、报告标签。
- Nightly 能比较 pass@1 / pass^k / token / latency。
- 能定位失败属于模型、工具、环境、Adapter 还是 Oracle。

### 长期完成

- WebArena、SWE-bench Lite、AgentDojo 安全子集稳定。
- OSWorld 只做可选 smoke。
- 项目再次改名时完成一次名称、路径和评测标识同步。
- 线上 Badcase 能从人工确认升级为自动转 Regression。
- 评测结果与版本发布决策绑定。

---

## 19. 推荐执行顺序

严格按依赖顺序：

1. 冻结 Trace / Case / Score / Result schema。
2. 补 Ledger `firstTokenAt`。
3. 做 Java test CLI。
4. 做 Python 侧车和 1 个 smoke Case。
5. 做 10 个 ReAct Case + 确定性 Scorer。
6. 接 DeepEval。
7. 接 agentevals。
8. 接 MRAG + Ragas。
9. 做数据分析 smoke。
10. 做 PR / Nightly / Release 报告。
11. 做真实 Ledger Trace 导出、脱敏和 5–10 个 Badcase 回流。
12. 做 trace-replay / counterfactual-replay 和私有 Regression Suite。
13. [已完成] 做 BenchmarkAdapter。
14. [已完成] 做阶段 2.5：实验内核强化，先完成 A/B、Cell 隔离、续跑和同分母统计。
15. [已完成] 接 tau² / GAIA / BIRD 子集；tau² airline 官方子集、GAIA Level 1
    两题和 BIRD 2026 Mini-Dev SQLite 8 题子集已跑通。
16. 接 Terminal-Bench / SWE-bench Lite。
17. 接 WebArena / AgentDojo。
18. 最后评估 OSWorld 和完整重型套件。

说明：第 11–12 项是阶段 1.5；第 13 项是阶段 2；第 14 项是新增的阶段 2.5。
tau² airline 子集、GAIA Level 1 两题和 BIRD 2026 Mini-Dev SQLite 8 题子集
已经接入；完整 GAIA 和后续重型 Benchmark 尚未接入。

不要在第 1–5 步未稳定时同时安装所有 Benchmark。那样得到的不是评测能力，而是不可归因的失败堆积。

---

## 20. 阶段一完成记录与阶段 1.5 实施清单

阶段一、阶段 1.5 已完成：

1. [已完成] 新增只读 `EvalTraceExportCli`，按 `runId` / `sessionId` / 时间窗读取 Execution Ledger。
2. [已完成] 新增密钥、PII、路径和链接的 Trace 脱敏器，并补负向测试。
3. [已完成] 导出统一 `trace.json`，复用阶段一 schema。
4. [已完成] 生成 `case-draft.yaml`，Oracle 保持空白，等待人工确认。
5. [已完成] 接入 `private-badcase`、`trace-replay`、`counterfactual-replay` 三种模式。
6. [已完成] 已导出 19 条真实 Run，并沉淀 5 个已确认 Badcase；
   覆盖模型、工具和环境失败，Oracle 与 Trace 引用齐全。
7. [已完成] 报告中把真实 Badcase 与自建 Case 分区，单独统计成功率。
8. [已完成] 阶段 1.5 验收通过，可进入公开 Benchmark Adapter。

每一步都以“测试先失败、实现后通过”、“真实数据不外泄”和
“不改变现有用户可见行为”为验收标准。
