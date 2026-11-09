# Agent 连续性与长期记忆评测开发计划

状态：阶段 9.0 / P0-1、P0-2、P0-3、P0-4、P0-5 已实现并验证；
阶段 9.1 最小闭环、内部 carry-forward 8 Case 与 `repeat=3` 稳定性基线已完成；
LongMemEval oracle 子集、MemoryAgentBench official-subset / official-full Scorer、
memory-isolation / memory-update、cache-efficiency A/B 以及 Nightly / Release
Workflow 已实现；LongMemEval cleaned 全量和 LoCoMo 仍为后续规划  
更新时间：2026-11-09  
适用仓库：`D:\Java Code\ai-agent\Cute-agent`  
依赖：现有 `evals/agent`、Inspect AI Runner、`BenchmarkAdapter`、A/B Cell、Execution Ledger、SSE 不参与评测

## 0. 一句话结论

上下文压缩、长期记忆、缓存命中率不是同一种能力，不能合成一个“Agent 总分”。

本计划拆成三个评测组合：

| 组合 | 评测问题 | 公开基准 | 项目自有控制组 |
|---|---|---|---|
| A. 上下文压缩 | 压缩后还能不能继续正确做事 | CompactBench | `compaction-carry-forward` |
| B. 长期记忆 | 跨会话、跨时间、跨干扰项还能不能记住并正确检索 | LongMemEval、MemoryAgentBench | `memory-isolation`、`memory-update` |
| C. 缓存与成本 | 相同任务下，前缀是否稳定，缓存是否命中，成本是否下降 | 没有统一的 Agent 排行榜，采用工程基线 | `cache-efficiency` |

三者的共同目标不是多接几个榜单，而是回答：

1. Agent 的上下文变短后，行为是否仍然正确。
2. Agent 面对历史信息时，是否能找回、更新、拒绝过期记忆。
3. Agent 在保持正确性的前提下，是否减少了重复 token 和总成本。

### 0.1 2026-11-08 实施与实测快照

已完成：

- LongMemEval Adapter 固定官方仓库 revision 和数据集 revision；默认
  `longmemeval_oracle.json`，`official-subset` 为六种题型各 2 题 + 2 道 abstention。
- `EvalMemorySeedCli` 支持官方历史日期、多 session、用户隔离和幂等 requestId；
  `latest_request_id` 按数据库 `VARCHAR(64)` 边界生成稳定短 ID。
- `memory-isolation` 4 Case、`memory-update` 2 Case；benchmark 模式使用生产工具目录，
  不再把无 tools 定义误替换成空工具集。
- `cache-efficiency` A/B；`schema-change` 只重排无语义 JSON 字段，不反转工具列表。
- Nightly / Release Workflow；各评测步骤允许继续执行，最后由 `ops gate` 统一阻塞。

真实运行结果（本地 `group/deepseek`，非官方 LongMemEval 裁判模型）：

| Suite | 结果 | 说明 |
|---|---|---|
| LongMemEval oracle `official-subset` 初始基线 | 14 题，QA 正确 12/14（85.71%），组合通过 10/14 | 3 题超过 80k token SLO；2 题答案错误；`scoreLabel=compatibility-subset` |
| LongMemEval oracle `official-subset` 优化后 | 连续 2 轮 14/14（100%），组合通过 14/14 | 两轮均无 unverified、无 80k token / 5 分钟 SLO 失败；`scoreLabel=compatibility-subset` |
| memory-isolation | 4/4 | 同用户召回、跨用户隔离、无记忆控制、拒答均通过 |
| memory-update | 2/2 | 最新地址覆盖旧地址、无记忆控制通过 |
| cache-efficiency schema A/B | 18/18 Cells | 中性字段重排后 cache delta 为 0，recommendation=`inconclusive`，未发现真实收益 |

LongMemEval 的 `group/deepseek` 结果不能写成官方榜单成绩；只有使用官方认可的
`gpt-4o` / `gpt-4o-mini` / `llama-3.1-70b-instruct` 系列裁判时，报告才允许标记为
官方兼容分数。

2026-11-09 稳定性优化只改运行契约与评测适配层，不改公开题目、标签、答案和 Oracle：

- 多会话题统一走 `browse` 后批量读取候选会话，避免搜索词变形漏检和预算收尾时少读会话。
- 拒答题要求精确职位名命中；相似职位不是别名，不得挪用其数字或事实。
- 裁判拒绝把空响应、`None` 或 `null` 当成 `no`，并给兼容裁判预留足够的 `max_tokens`。
- 预算收尾提前到 80% 软阈值，终答协议禁止 `$$$` / `None` 覆盖 `FINAL_ANSWER:`。
- 最终连续两轮报告：`D:\cute-eval-tmp\longmemeval-subset-final12\report.json` 与
  `D:\cute-eval-tmp\longmemeval-subset-final13\report.json`；两轮平均每题
  42,977 / 41,184 tokens，工具调用平均 3.93 / 3.50 次，单轮累计耗时
  166,788 / 164,150 ms。

## 1. 不可突破的边界

### 1.1 公开 Benchmark 只读

接入任何公开 Benchmark 时：

- 不修改官方题目、数据、标签、工具定义、环境快照和官方 scorer。
- 仓库只保存 Adapter、manifest、测试和运行说明。
- 大体积数据和模型缓存放在 `EVAL_DATA_HOME`。
- 上游版本使用 commit、release 或 dataset revision 固定。
- 官方 evaluator 不可用时，结果只能是 `unverified`，不能伪造官方成绩。
- 要改题、补题或换 Oracle 时，另建 `Cute-Agent Bench` 的 internal suite。

### 1.2 Execution Ledger 是唯一执行事实源

评测数据流保持：

```text
Execution Ledger
  -> Java 只读导出/评测 Fixture CLI
  -> sanitized trace.json
  -> Python Scorer / Benchmark Adapter
  -> report.json / report.html
```

Python 不连接生产数据库，不读取 Java 实体，不通过 SSE 判断任务成败。

### 1.3 评分分层

每个结果至少包含：

- `official`：官方题目和官方 evaluator。
- `official-subset`：官方题目子集和官方 evaluator。
- `internal`：项目自建题目、Fixture 或控制组。
- `unverified`：证据不足、数据缺失或 Adapter 无法无损接入。

不允许把 `internal` 结果写成官方榜单成绩，也不允许把缓存命中率直接写成任务正确率。

### 1.4 不接第三方观测平台

本项目只使用本机 Java Agent 端的 Execution Ledger 作为执行事实源。

明确不接入：

- OpenTelemetry Collector、Tempo、Jaeger。
- OpenInference。
- Langfuse、Phoenix、Opik 或其他观测/评测平台。
- `TraceSource` 抽象和 `trace_parity` 对账。

评测需要的字段全部从 Java Ledger 只读导出到 `trace.json`；缺失字段由 Java 采集层补齐，
不通过外部平台反推，也不依赖第三方平台的采样、保留期或查询 API。

## 2. 统一架构

### 2.1 总体结构

```text
                         ┌──────────────────────────────┐
                         │ Inspect AI / cute-evals      │
                         │ 单一 Runner + A/B 实验内核   │
                         └──────────────┬───────────────┘
                                        │
             ┌──────────────────────────┼──────────────────────────┐
             │                          │                          │
   CompactBench Adapter       Memory Benchmark Adapter      Cache Suite
             │                          │                          │
             └──────────────────────────┼──────────────────────────┘
                                        │
                         CuteAgentAdapter / Java Eval CLI
                                        │
             ┌──────────────────────────┼──────────────────────────┐
             │                          │                          │
       Real Compactor            Working Memory / LTM       LLM Invocation
       SessionContext...         session_search / memory     cache usage
             │                          │                          │
             └──────────────────────────┴──────────────────────────┘
                                        │
                         Execution Ledger / normalized Trace
                                        │
                         Deterministic Oracle / Official Oracle
                                        │
                         report.json / report.html / baseline diff
```

### 2.2 复用原则

不新建第二套评测平台。新增能力复用：

- `BenchmarkAdapter`：公开任务的 prepare / run / collect / score 生命周期。
- `EvalCase`：内部 Case、Fixture、预算和确定性 Oracle。
- A/B `Cell`：baseline、candidate、重复、隔离、续跑和同分母统计。
- `ScoreRecord`：所有 Scorer 的统一输出。
- `report.json`：唯一机器可读真源。
- `ops`：历史、趋势、矩阵、发布门禁和 Badcase 回流。

### 2.3 新增职责

只新增以下薄层：

| 新增组件 | 职责 | 不负责 |
|---|---|---|
| `CompactBenchAdapter` | 官方 CompactBench 任务和数据转换、Compactor method 桥接 | 改题、改 scorer、改官方输出 |
| `MemoryBenchmarkAdapter` | 官方记忆任务的会话历史装载、查询、收集 | 直接写数据库、改官方标签 |
| `CacheEfficiencySuite` | 项目自有缓存/成本对照任务 | 把缓存命中当作任务成功率 |
| `EvalCompactionCli` | 用生产压缩服务压缩一段给定会话 | 在 CLI 内重写压缩算法 |
| `EvalMemorySeedCli` | 用评测 profile 种入会话、工作记忆和 LTM | 用 Python 直接拼 SQL |
| `ContextEventProjector` | 把压缩、记忆写入、记忆检索投影成通用 Trace 事件 | 把 Benchmark 私有字段塞进通用 Trace |

### 2.4 外部项目的接入形态

这些开源项目不是同一种东西，安装方式不能混为一谈：

| 项目 | 形态 | 建议接入方式 |
|---|---|---|
| CompactBench | Python Benchmark / Harness + 数据 | 固定 commit，作为 `evals/agent` 的可选评测依赖；数据放 `EVAL_DATA_HOME` |
| LongMemEval | 数据集 + 官方 evaluator | 固定 dataset revision；Adapter 负责会话装载和答案收集，评测器原样调用 |
| MemoryAgentBench | 数据集 + Harness / 评测协议 | 固定 commit；只接 Adapter 和装载桥，不改官方 chunk 和问题 |
| LoCoMo | 数据集 + 评测脚本 | 后续可选，先确认官方 harness 和许可 |
| cache-efficiency | 项目自有 Suite | 只依赖现有 Java Runner 和 Ledger，不新增外部 Benchmark 包 |

安装必须做到：

- 不把公开 Benchmark 的数据打进生产包。
- 不让外部 Harness 依赖进入产品运行时。
- 不在 `pyproject.toml` 默认依赖里塞入所有重型 Benchmark。
- 通过 extra 或独立脚本按需安装，并锁定 `uv.lock`。

## 3. Trace 与字段契约

### 3.1 P0-1：补齐 Ledger 到 Trace 的 LLM 事实字段

状态：已实现（2026-11-08）。

P0-1 是阶段 9 的硬前置条件，不是可选项。它只做“已有事实的导出补全”，
不改变 Agent 控制流，不改变用户可见输出，不新增数据库列。

#### 3.1.1 背景

数据库里的 `ai_agent_llm_invocation` 已经有缓存、估算 token 和 prompt 形状字段，
但当前 `LlmInvocationView` 和 Trace 导出层没有把这些字段完整带出来，导致：

- Python 只能看到总 token，不能计算真实缓存命中率。
- 无法区分 provider 实际缓存命中与本地 `cacheStatus` 风险判断。
- 无法分析压缩、工具 schema 变化和消息数量变化对缓存的影响。
- 无法用 token 估算值与 provider 实际值做偏差校正。

#### 3.1.2 P0-1 必须导出的字段（数据库已有）

| 字段 | 来源 | 允许为空 | 用途 |
|---|---|---|---|
| `cachedPromptTokens` | provider usage | 是 | 真实缓存命中 token |
| `promptTextTokens` | provider usage | 是 | 文本输入 token |
| `promptAudioTokens` | provider usage | 是 | 音频输入 token |
| `promptImageTokens` | provider usage | 是 | 图片输入 token |
| `completionTextTokens` | provider usage | 是 | 文本输出 token |
| `completionAudioTokens` | provider usage | 是 | 音频输出 token |
| `reasoningTokens` | provider usage | 是 | 推理 token 成本 |
| `systemFingerprint` | TokenCounter | 是 | 本地 System Prompt 形状 |
| `estTotalTokens` | TokenCounter | 是 | 本地总 token 估算 |
| `estSystemTokens` | TokenCounter | 是 | 本地系统段估算 |
| `estMessageTokens` | TokenCounter | 是 | 本地消息段估算 |
| `estToolTokens` | TokenCounter | 是 | 本地工具段估算 |
| `messageCount` | TokenCounter | 是 | 当次请求消息数 |
| `toolCount` | TokenCounter | 是 | 当次请求工具数 |
| `cacheStatus` | LlmPromptObservability | 是 | 本地缓存风险状态 |
| `cacheRiskFlags` | LlmPromptObservability | 是 | 本地缓存风险原因 |

以上 16 个字段全部属于“数据库已经有、评测 Trace 还没有”的事实。
本轮只做字段透传，不新增数据库列，不修改 Agent 控制流。

多模态 token 明细允许为空。数据库有值时原样导出，数据库没有值时导出
`null`，绝不合成 `0`。只有 provider 明确返回 `0` 时才表示真实的零值。

#### 3.1.3 P0-1 修改落点

1. `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/ledger/model/LlmInvocationView.java`
   - 增加上述字段。
   - 保持字段类型可空，禁止使用原始类型 `int`。
2. `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/ledger/impl/ExecutionLedgerQueryServiceImpl.java`
   - 在实体转 View 的位置补字段映射。
   - 当前问题定位在约 `:593`，实施时以实际代码为准。
3. `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/EvalFixtureRunnerCli.java`
   - 在 Trace 导出位置补字段序列化。
   - 当前问题定位在约 `:209`，实施时以实际代码为准。
4. `evals/agent/schemas/trace.schema.json`
   - 在 `llmInvocations[]` 增加可选字段。
   - 旧 Trace 没有这些字段仍然合法。
5. `evals/agent/src/cute_evals/models.py`
   - 保持原始字段透传。
   - 增加仅供 Scorer 使用的缓存/估算 token 读取方法；缺失时返回 `None`。
6. `evals/agent/src/cute_evals/report.py`
   - 只在实际值存在时汇总；缺失值不能自动变成 0。

字段命名保持 camelCase，与现有 Trace 一致：

```json
{
  "id": 42,
  "promptTokens": 18000,
  "completionTokens": 900,
  "totalTokens": 18900,
  "cachedPromptTokens": 12000,
  "reasoningTokens": 120,
  "systemFingerprint": "sha256:...",
  "estTotalTokens": 17820,
  "estSystemTokens": 4200,
  "estMessageTokens": 11200,
  "estToolTokens": 2420,
  "messageCount": 18,
  "toolCount": 9,
  "cacheStatus": "OK",
  "cacheRiskFlags": null
}
```

#### 3.1.4 P0-1 语义约束

- `cachedPromptTokens` 是 provider 返回值，不是本地推断值。
- `cacheStatus=OK` 只表示本地没有发现明显的 prompt 前缀变化，不表示 provider 一定命中。
- `cacheStatus=MISS` 需要保留 `cacheRiskFlags` 作为解释。
- `systemFingerprint` 是本地 System Prompt hash，不写 provider 缓存键。
- 估算 token 与 provider token 同时存在时，两者都保留，不互相覆盖。
- 文本、图片、音频 token 缺失时保持 `null`；报表显示 `n/a`。
- 旧数据、旧 Trace 和旧报告必须继续可读。

#### 3.1.5 P0-1 测试

Java：

- View 映射测试：实体有值、实体为 null、混合字段三种情况。
- 导出器测试：`cachedPromptTokens=0` 与字段缺失必须是两种不同结果。
- 兼容测试：旧 Trace JSON 不含新字段仍能通过 schema 校验。

Python：

- Trace 反序列化测试。
- 缓存命中率测试：`cachedPromptTokens=null` 时返回 `unverified`，不能按 0 计算。
- 报告测试：部分 invocation 有缓存值、部分没有时，只汇总有效样本并明确样本数。

数据验收：

- 从真实评测库导出一条包含 LLM invocation 的 Run。
- 至少一个 invocation 有 `cachedPromptTokens > 0`。
- Python 能读取全部 16 个字段，包括 `cachedPromptTokens`、`reasoningTokens`、
  `est*`、多模态 token 明细、`messageCount`、`toolCount` 和 `cacheStatus`。
- 同一份数据导出两次结果一致。

#### 3.1.6 P0-1 退出条件

- 上面 16 个字段能从真实 Ledger 进入 Trace。
- 缺失字段是 `null`，不是 0。
- Python 能基于实际缓存字段计算 cache hit。
- 旧 Trace、旧报告和 PR fixture 不回归。
- 不修改产品控制流、数据库 schema 或 SSE 协议。

### 3.2 新增通用上下文事件

在 `trace.schema.json` 增加 `contextEvents`，不把 CompactBench 或 LongMemEval
的私有字段放进通用 schema：

```json
{
  "eventId": "ctx-...",
  "type": "compaction",
  "sessionId": "session-...",
  "requestId": "request-...",
  "status": "success",
  "strategy": "full-llm",
  "beforeTokens": 42000,
  "afterTokens": 12000,
  "beforeMessageCount": 80,
  "afterMessageCount": 24,
  "summaryHash": "sha256:...",
  "errorCode": null,
  "startedAt": "2026-11-07T10:00:00.000",
  "finishedAt": "2026-11-07T10:00:02.000"
}
```

允许的通用事件类型先限定为：

- `compaction`
- `memory_write`
- `memory_read`
- `memory_retrieval`
- `memory_fork`
- `context_reset`

大段摘要和历史消息不直接写入评测 Trace。Trace 只保存 hash、计数、状态和相对引用；
原始内容留在脱敏后的专用工作目录。

### 3.3 新增记忆事件

`memoryEvents` 只记录可验证事实：

- 操作类型：写、读、检索、后台整理、压缩前 flush。
- scope：session / user / sub-agent。
- owner 类型和 hash，不写用户明文身份。
- 写入前后条目数、应用条目数、读取返回数。
- 检索 query hash、命中 ID hash、耗时。
- 状态、错误码、是否发生越权或隔离失败。

不记录：

- LTM 原始内容。
- 用户隐私字段。
- 浏览器 Cookie、Authorization、密钥。
- Benchmark 私有标签和答案。

### 3.4 新增 run 字段

建议补充以下通用字段：

- `turnIndex`
- `parentRequestId`
- `contextEpoch`
- `compactionEpoch`
- `sessionPhase`

这些字段用于回答“这是第几轮、压缩发生过几次、当前查询属于哪个上下文代次”，
不改变用户可见行为，也不依赖 SSE。

### 3.5 完整执行事实补齐与字段建议

这一节把“数据库已经有、但评测 Trace 看不到”的事实全部收口，同时定义
哪些本机 Java 侧字段值得新增，哪些字段不应该进入 Execution Ledger。

#### 3.5.1 设计原则

1. `Execution Ledger` 继续是执行事实源，`trace.json` 是它的评测投影。
2. 先补“已有但未导出”，再补“确实需要但尚未采集”；不为了字段齐全制造假数据。
3. 只补能回答评测问题的字段，不为了字段齐全制造没有评测用途的数据。
4. 业务字段统一使用 camelCase，不再为第三方语义约定设计别名。
5. 未知值使用 `null`；只有 provider 明确返回 `0` 时才写 `0`。
6. 大型内容、完整 Prompt、工具结果、Artifact 正文和隐私数据不进入通用 Trace。

#### 3.5.2 P0-2：Run、用户和子 Agent 字段

状态：已实现（2026-11-08）。

这些字段已经在数据库或现有 View 中，只需要补齐到 Trace：

| Trace 字段 | 来源 | 处理方式 |
|---|---|---|
| `run.runUid` | `DialogueRun.runUid` | 原样导出，作为对外稳定运行标识 |
| `run.userIdHash` | `DialogueRun.userId` | 只导出脱敏 hash，不导出明文用户 ID |
| `toolInvocations[].subAgentId` | `ToolInvocation.subAgentId` | 原样导出 |
| `toolInvocations[].subAgentType` | `ToolInvocation.subAgentType` | 原样导出 |
| `toolInvocations[].subAgentDescription` | `ToolInvocation.subAgentDescription` | 原样导出 |
| `toolInvocations[].artifactCount` | `ToolInvocationView.artifactCount` | 原样导出；无值写 `null` |

子 Agent 关系仍以 `parentToolCallId` 为主，不新增一套业务父子 ID，
也不引入第三方 Span 树。

#### 3.5.3 P0-3：压缩事件导出

状态：已实现（2026-11-08）。

压缩事件来自 `ai_agent_working_memory_compaction`，进入统一的
`contextEvents[]`，事件类型为 `compaction`。

验证：Java 单测覆盖成功/失败、摘要 hash、错误码、单 Run 与批量回放；
真实只读导出 `1790848391014-4144` 已成功执行（该 Run 当前没有压缩事件，
返回 `contextEvents=[]`，因此真实库链路已验证，事件内容仍由 Fixture 回归覆盖）。

| 事件字段 | 来源 | 说明 |
|---|---|---|
| `eventId` | `id` | 稳定事件 ID |
| `type` | 固定值 | `compaction` |
| `sessionId` | `sessionId` | 会话关联 |
| `triggerRequestId` | `triggerRequestId` | 触发压缩的请求 |
| `compactRequestId` | `compactRequestId` | 压缩子请求 |
| `status` | `status` | success / failed |
| `strategy` | `strategy` | 压缩策略 |
| `beforeTokens` | `beforeTokens` | 压缩前 Token |
| `afterTokens` | `afterTokens` | 压缩后 Token |
| `beforeMessageCount` | `beforeMessageCount` | 压缩前消息数 |
| `afterMessageCount` | `afterMessageCount` | 压缩后消息数 |
| `thresholdTokens` | `thresholdTokens` | 触发阈值 |
| `summaryHash` | `summaryText` 计算 | 摘要 hash，不直接写入摘要正文 |
| `errorCode` | `errorMessage` 归类 | 失败类型；原错误只留在脱敏审计存储 |
| `startedAt` | 事件时间 | 能取到才填，否则 `null` |
| `finishedAt` | 事件时间 | 能取到才填，否则 `null` |

约束：

- 不新增第二个 `compactions[]` 真相源；统一由 `contextEvents[]` 表达，
  报告层可以派生出 `compactions` 视图。
- `beforeMessagesJson` / `afterMessagesJson` 不进入通用 Trace。
- 摘要正文只保存 hash 和相对引用；需要人工审计时从脱敏后的专用目录读取。
- 压缩事件必须能和 `triggerRequestId` 关联到具体 Run。
- 审计表只有 `create_time`，没有精确的开始时间；因此 `startedAt=null`，
  `finishedAt=createTime`，后者只作为完成时间上界。

#### 3.5.4 P0-4：LTM fork 事件导出

状态：已实现（2026-11-08）。

`ai_agent_ltm_fork_execution` 进入 `memoryEvents[]`，事件类型为
`memory_fork`。

| 事件字段 | 来源 | 说明 |
|---|---|---|
| `eventId` | `id` | 稳定事件 ID |
| `type` | 固定值 | `memory_fork` |
| `sessionId` | `sessionId` | 会话关联 |
| `triggerRequestId` | `triggerRequestId` | 触发请求 |
| `forkRequestId` | `forkRequestId` | 独立 fork 请求 |
| `forkKind` | `forkKind` | flush / background review |
| `status` | `status` | success / failed / skipped / timeout |
| `skipReason` | `skipReason` | 跳过原因，脱敏后保存 |
| `ownerType` / `ownerHash` | `ownerType` / `ownerId` | owner ID 只保存 hash |
| `userTurns` | `userTurns` | 用户轮数 |
| `snapshotMessageCount` | `snapshotMessageCount` | 快照消息数 |
| `maxSteps` / `timeoutSeconds` | 原字段 | 执行预算 |
| `durationMs` | 原字段 | 执行耗时 |
| `entriesBefore` / `entriesAfter` | 原字段 | 记忆条目变化 |
| `appliedCount` | 原字段 | 实际应用条目数 |
| `errorCode` | `errorMessage` 归类 | 失败类型 |

这是“记忆整理任务执行事实”，不是完整的记忆读取事实。LongMemEval 需要的
`memory.read`、`memory.write` 和检索命中证据，另行由 P1 记忆事件补齐。

约束：

- `ownerId` 只导出为 `ownerHash`，不导出明文。
- `skipReason` 只保留固定枚举值，未知值统一写成 `other`。
- `detailJson`、原始 `errorMessage` 不进入通用 Trace。
- 审计表只有 `create_time`，因此 `startedAt=null`，`finishedAt=createTime`。

验证：Java 单测覆盖成功、超时、跳过、owner hash、未知 skip reason 收敛和
单 Run/批量回放；真实只读导出 `1790848391014-4144` 已成功执行
（该 Run 当前没有 LTM fork 事件，返回 `memoryEvents=[]`）。

#### 3.5.5 P0-5：conversation 与环境数组

状态：已实现（2026-11-08）。

`trace.schema.json` 已有以下数组，但当前 Ledger 导出没有填写：

```text
conversation[]
environmentEvents[]
environmentSnapshots[]
```

补齐规则：

| 数组 | 数据来源 | 失败时 |
|---|---|---|
| `conversation` | `ai_agent_working_memory_turn` + `ai_agent_working_memory_message` | 输出空数组，并写 `conversationSource=unavailable` |
| `environmentEvents` | 浏览器、数据库、文件系统等显式事件 sink | 输出空数组，不推测、不从 final answer 反推 |
| `environmentSnapshots` | 评测环境生成的显式快照 | 输出空数组，只保存 hash、类型和相对引用 |

`conversation` 的最小映射：

| Trace 字段 | 工作记忆来源 |
|---|---|
| `turnId` | `ai_agent_working_memory_turn.id` |
| `turnIndex` | `ai_agent_working_memory_turn.turn_seq` |
| `requestId` | `ai_agent_working_memory_turn.request_id` |
| `runId` | `ai_agent_working_memory_turn.run_id` |
| `messageId` | `ai_agent_working_memory_message.id` |
| `seqNo` | `ai_agent_working_memory_message.seq_no` |
| `role` | `ai_agent_working_memory_message.role` |
| `content` | `ai_agent_working_memory_message.content`，落盘前脱敏 |
| `toolCallId` | `ai_agent_working_memory_message.tool_call_id` |
| `toolCalls` | `ai_agent_working_memory_message.tool_calls_json`，按结构化 JSON 解析 |
| `messageKind` / `visibility` | 同名数据库字段 |
| `tokenEstimate` | `ai_agent_working_memory_message.token_estimate` |

禁止：

- 从 `finalSummaryText` 反推用户回合。
- 从工具结果反推数据库最终状态。
- 把浏览器 DOM、数据库内容或文件正文整段塞进通用 Trace。
- 把 `base64Image` 原样写入通用 Trace。

新增 `conversationSource`、`environmentSource` 和 `snapshotSource` 三个状态字段，
用于区分“没有数据”和“数据源不可用”。这样 Python 不会把空数组误判为
“Agent 没有对话”。

实现约束：

- `conversationSource=working_memory` 表示工作记忆读取成功；即使消息为空也保持
  这个值，表示“确实没有消息”。
- 读取失败时 `conversation=[]` 且 `conversationSource=unavailable`。
- 当前没有浏览器/数据库/文件系统显式事件 sink，因此
  `environmentSource=unavailable`、`snapshotSource=unavailable`，数组保持空。
- `base64Image`、`reasoningContent` 和 `MODEL_ONLY` 消息不进入通用 Trace。

验证：真实只读导出 `1790848391014-4144` 已从工作记忆导出 832 条消息，
`conversationSource=working_memory`；环境事件和快照均为空并明确标记
`environmentSource=unavailable`、`snapshotSource=unavailable`；
导出结果通过 `trace.schema.json` 校验。

#### 3.5.6 推荐新增字段：按阶段分层

字段设计优先服从本机 Java Ledger 的评测需要，不为了兼容第三方观测平台增加字段。
建议只新增以下两档。

| 阶段 | 字段组 | 具体字段 | 数据来源 | 目的 |
|---|---|---|---|---|
| P0 | Run 关联 | `runUid`、`userIdHash`、`parentRequestId`、`turnIndex`、`contextEpoch`、`compactionEpoch` | 现有 Run/会话事实 | 跨 Run、跨回合关联 |
| P0 | LLM 观测 | P0-1 的 16 个字段 | 现有 LLM 表 | 缓存、成本、上下文构成 |
| P0 | Tool 观测 | `subAgentId`、`subAgentType`、`subAgentDescription`、`artifactCount` | 现有 Tool View | 还原子 Agent 和产物归属 |
| P0 | Context 事件 | compaction 和 LTM fork 字段 | 现有两张事件表 | 压缩与记忆执行审计 |
| P1 | LLM 请求配置 | `providerName`、`requestedModel`、`responseModel`、`modelParameters`、`providerRequestId`、`retryCount`、`fallbackFromModel` | Provider 适配层 | 复现实验、解释模型变化 |
| P1 | Prompt 形状 | `systemPromptHash`、`inputMessagesHash`、`toolsHash`、`toolDefinitionsVersion` | LLM 适配层 | 分析 Prompt 偏移和缓存分段 |
| P1 | Tool 质量 | `toolDefinitionHash`、`toolSchemaVersion`、`inputHash`、`outputHash`、`outputBytes`、`retryCount`、`errorType`、`timeoutMs` | Tool 执行层 | 评估工具选择、失败和重试 |
| P1 | 成本 | `promptCost`、`completionCost`、`cacheCost`、`totalCost`、`currency` | 价格表或 Provider usage | 单任务成本 |
| P1 | Memory | `memoryScope`、`ownerHash`、`readCount`、`writeCount`、`retrievedIdHashes`、`hitCount`、`leakStatus` | Memory 服务 | LongMemEval 和隔离测试 |
| P1 | RAG | `retrievalQueryHash`、`topK`、`documents[]`（docIdHash、rank、score）、`citationCount`、`groundingStatus` | Retriever | RAG 召回、引用和 groundedness |
| P1 | 评测上下文 | `datasetId`、`datasetVersion`、`split`、`taskId`、`repeatIndex`、`seed`、`modelConfigHash`、`harnessVersion`、`environmentDigest` | Benchmark Adapter | 官方提交和重复统计 |

字段落点：

- 业务 Trace 使用 camelCase。
- 所有字段都来自 Java Execution Ledger 的只读导出。
- Python 不直接连接产品数据库，也不依赖第三方观测平台的私有格式。

#### 3.5.7 不新增的字段

以下字段即使能采集，也不建议写入通用 Trace：

- 完整 System Prompt、完整用户消息和完整工具结果。
- Provider 的隐藏推理内容或模型内部 CoT。
- 文件正文、数据库全量快照、浏览器 DOM 全文。
- Cookie、Authorization、密钥、用户明文身份。
- 官方 Benchmark 的标准答案、私有标签和隐藏测试。
- 只对上架/计费有意义、不能用于评测的供应商内部字段。

#### 3.5.8 字段注册表与兼容

新增 `evals/agent/schemas/trace-fields.json` 作为字段注册表，每个字段至少记录：

```json
{
  "key": "llm.cachedPromptTokens",
  "type": "integer",
  "nullable": true,
  "stage": "p0",
  "source": "provider_usage",
  "requiredForScore": false,
  "privacy": "none"
}
```

约束：

- `trace.schema.json` 的字段必须能在注册表找到。
- 新增可选字段不破坏旧 Trace；旧 Trace 缺少字段时 Python 读成 `None`。
- 新增必需字段必须提升 `schemaVersion` 并增加迁移测试。
- 报告必须区分字段缺失、字段为 `null`、字段为真实的 `0`。
- 本阶段新增字段发布时使用 `schemaVersion=1.1`；旧 `1.0` 继续可读。

## 4. 组合 A：上下文压缩评测

### 4.1 评测目标

压缩评测不能只看“摘要是否变短”，必须回答四个问题：

1. 压缩产物是否完整：目标、约束、已完成动作、当前状态、下一步是否保留。
2. 压缩产物是否可用：后续任务是否仍能正确完成。
3. 压缩是否安全：失败时是否保留原上下文或显式降级，不丢事实。
4. 压缩是否值得：节省了多少 token，增加了多少恢复调用和延迟。

### 4.2 公开部分：CompactBench

优先接入 `compactbench/compactbench`：

- 上游：`https://github.com/compactbench/compactbench`

- 使用官方任务、官方多轮压缩流程、官方隐藏选项评分。
- 固定 upstream commit、Python 版本和运行参数。
- 用 `CompactBenchAdapter` 把 Cute Agent 的真实压缩器包装成一个官方允许的 compactor method。
- 不把 Cute Agent 的 Benchmark 私有字段写回 CompactBench 数据集。
- 官方 scorer 可用时报告 `official` 或 `official-subset`。

接入顺序：

1. 先跑官方的最小 smoke，确认 compactor 输入输出协议。
2. 再接官方 validation / 可用 split 的固定子集。
3. 最后再跑官方全量；全量不进入 PR。

建议先实现一个 Java 侧 `EvalCompactionCli`：

```text
stdin:  {sessionId, requestId, messages, budget}
stdout: {messages, strategy, beforeTokens, afterTokens, summaryHash, eventId}
```

Adapter 只负责把 CompactBench 的 transcript 转成该协议，不复制压缩逻辑。

实现状态（2026-11-08）：

- `EvalCompactionCli` 已接入生产 `SessionContextCompactionServiceImpl`，并返回
  `summaryText`、`summaryHash`、strategy、前后 Token、审计事件 ID 和失败原因。
- `java_method.py` 已按 CompactBench 0.3 的 `CompactionRequest` /
  `CompactionArtifact` 协议桥接 Java；cycle 1 之后只发送上一版 artifact 和新增
  turns，不重放旧 turns。
- CompactBench 0.3 尚未发布 PyPI，当前固定源码 zip 的 SHA-256 为
  `8fd94d802cd8f44897541a39fe8b83697e496466bc96cf361b10f3ad5f086eae`；
  PyPI 0.1.0 因协议过期不兼容。
- 已真实跑通自定义 Java 压缩 smoke：`full-llm+drop-oldest`，摘要包含
  Goal、Constraints & Preferences、Active State，失败时不再静默伪装成功。
- 已真实跑通官方 `starter@2.0.1` 的 8 族最小子集（每族 1 题、0 drift）：
  official scorer `3.0.0`，score `0.828`，compression ratio `1.7x`，
  budget violations `0`，reader error `0%`，trap `0%`，hallucination `0%`，
  stale-answer `33.3%`。`reference_resolution=0.000`、
  `task_continuity=0.750`、`entity_confusion=0.875` 是下一轮优先分析项；
  其中 `task_continuity` 该次走的是 Java `local-fallback`，不是 full-LLM。
- 该次运行使用 `--drift-cycles 0`，因此不能据此声称多 cycle 抗漂移能力，
  也不能冒充 leaderboard 全量成绩。

### 4.3 项目自有部分：carry-forward

来源标注必须明确：本节的 8 个 Case 是项目自建 `internal` 评测，不是
CompactBench 官方题，也不能写成公开榜单成绩。公开部分只有 4.2 的
CompactBench Adapter，报告中只能标 `official-subset`。

新增内部任务族：

```text
evals/agent/cases/ab/compaction-carry-forward/
```

现有 `evals/agent/cases/ab/compaction-recall` 是脚本化 Fixture，它只能证明
评测契约，不等于真实 Java 压缩器被触发。当前评测库中 compaction event 也是
0 行，因此新 Case 必须显式触发真实生产压缩路径，不能复用旧 Fixture 的结论。

每个 Case 至少包含：

1. 目标。
2. 约束。
3. 已完成的工具动作。
4. 当前未完成状态。
5. 下一步。
6. 强制触发真实压缩的阈值或 Fixture。
7. 压缩后只发送一句“继续”或同等简短指令。

Oracle：

- 目标是否保留。
- 约束是否保留。
- 下一步是否保留。
- 是否重复已完成动作。
- 是否重复询问已经回答过的问题。
- 是否编造未发生的记忆。
- 是否能通过 `session_search` 或 `memory` 找回被压缩的细节。

必须包含：

- `uncompressed-control`：不压缩的对照组。
- `compressed`：真实压缩组。
- `missing-active-state`：摘要缺少当前状态时，要求显式恢复或承认未知，禁止编造。
- `false-memory`：用户没有说过的信息，模型不能凭空补全。
- `pointer-recovery`：压缩后还能通过历史检索找到原消息 ID 或关键事实。
- `multi-fact`：多个约束同时存在，检查摘要是否只保留最后一个。

### 4.4 压缩前置修复

在开发真实 carry-forward 前，先修复当前已知缺陷：

1. `SessionContextCompactionServiceImpl.java:783` 直接截断 JSON，超过 64K
   后审计 JSON 非法，不能重建。
2. 永久压缩失败的 `abort-unchanged` 与接口“失败降级 drop-oldest”的契约不一致。
3. 摘要只校验非空，没有校验 Goal、Constraints、Active State 是否齐全。

修复原则：

- 审计 payload 必须是合法 JSON；超限时保存截断声明和 hash，不能生成坏 JSON。
- 生产失败策略只保留一个事实：要么 drop-oldest，要么显式失败；不能两头不一致。
- 摘要结构缺失时，后续测试必须能定位缺失字段，不能只判“摘要非空”。

### 4.5 指标

| 指标 | 定义 |
|---|---|
| `compaction_triggered` | 是否真实触发压缩 |
| `compaction_strategy` | full-llm / local-fallback / drop-oldest / abort |
| `compression_ratio` | `beforeTokens / afterTokens` |
| `goal_preserved` | 目标是否保留 |
| `constraints_preserved` | 约束是否保留 |
| `next_step_preserved` | 下一步是否保留 |
| `carry_forward_success` | 压缩后继续任务是否成功 |
| `repeat_question_rate` | 是否重复询问已确认事实 |
| `false_memory_rate` | 是否编造历史事实 |
| `recovery_tool_calls` | 压缩后额外检索/恢复工具调用数 |
| `extra_tokens_after_compaction` | 压缩后恢复所需 token |
| `compaction_latency_ms` | 压缩耗时 |
| `pass@1` / `pass^k` | 单次与重复稳定性 |

`carry_forward_success` 只在真实触发压缩的运行上计算，分母必须写清楚。

### 4.6 退出条件

- CompactBench Adapter 不修改官方数据和 scorer。
- 真实 Java 压缩器可以通过 CLI 被 CompactBench 调用。
- 内部的 compressed 与 uncompressed control 使用同一任务、模型、seed 和预算。
- 至少 6 个 carry-forward Case 能真实触发压缩。
- 负样本能区分“恢复成功”“明确未知”和“编造记忆”。
- 压缩失败不会产生非法审计 JSON。
- 报告能回答“压缩省了多少 token，续航损失是多少”。

## 5. 组合 B：长期记忆评测

### 5.1 评测目标

长期记忆不是“有没有一个 memory 工具”，而是：

- 能不能写入正确的耐久事实。
- 能不能在需要时检索到。
- 能不能区分不同用户、不同会话、不同子 Agent。
- 能不能处理时间变化和知识更新。
- 能不能在证据不足时明确说不知道。

### 5.2 公开部分：LongMemEval

首选 `xiaowu0162/longmemeval`：

- 代码：`https://github.com/xiaowu0162/LongMemEval`
- 数据：`https://huggingface.co/datasets/xiaowu0162/longmemeval`

1. 先接入 `longmemeval_oracle`，用于验证环境、会话装载和 scorer。
2. 再接入 `longmemeval_s_cleaned`，验证长历史下的检索能力。
3. 全程使用官方问题、官方答案和官方 evaluator。
4. 如果官方数据需要重建会话历史，通过 Java `EvalMemorySeedCli` 写入评测库。
5. 不把 LongMemEval 会话内容和答案写进公开 Trace。

`EvalMemorySeedCli` 输入示例：

```json
{
  "ownerHash": "sha256:...",
  "scope": "user",
  "sessions": [
    {
      "sessionId": "eval-...",
      "turns": [
        {"role": "user", "content": "..."},
        {"role": "assistant", "content": "..."}
      ],
      "timestamp": "2024-01-01T10:00:00Z"
    }
  ]
}
```

Java 侧通过评测 profile 的正式写入路径装载，不绕过 scope、owner 和权限检查。

### 5.3 公开部分：MemoryAgentBench

接入 `HUST-AI-HYZ/MemoryAgentBench` 的官方子集：

- 上游：`https://github.com/HUST-AI-HYZ/MemoryAgentBench`

- 覆盖 Accurate Retrieval。
- 覆盖 Test-Time Learning。
- 覆盖 Long-Range Understanding。
- 覆盖 Conflict Resolution。
- 使用官方数据、官方协议和官方 evaluator。

先跑小规模子集，验证：

- 每个 chunk 的装载顺序。
- 时间戳和会话边界。
- 查询时 memory / session_search 是否被允许。
- 结果是否能被官方 evaluator 直接消费。

实现状态（2026-11-09）：**official-subset 已实现。**

- 固定 GitHub commit `538026089d1a8a8eff05121d0db89b388f360eba` 和 HF revision
  `7ea066982b140a19337e17e60d45d4076e042faf`。
- 四个 category 各选一个官方 context，每个 context 取前 5 道官方题，共 20 题。
- context 使用官方 `chunk_text_into_sentences` 的 4096-token 规则切分，按顺序写入
  同一评测 user 下的隔离 retrieval session；同一 context 只注入一次，随后连续执行
  5 个 query。
- exact-match / substring-match 和 query template 从固定 upstream revision 复制，
  使用官方 `agentic_memory_agent` 版本，SHA-256 锁定在 manifest。
- `official-full` 已接入全部 22 个官方 source、146 个 context 和 3,671 道题；
  LongMemEval LLM judge、InfBench 三分项 F1、Ruler recall 和 Recsys Recall@1/5/10
  都使用固定 upstream 的评分规则。
- 官方裁判模型不在 manifest 白名单时，报告只标记 `compatibility-full`；当前尚未
  执行 3,671 题全量 live 跑分，不能把 Adapter 能力写成官方榜单成绩。

2026-11-09 live 单次验收：20/20 个 turn 成功，题目级 15/20 = 75%；AR 5/5、
TTL 4/5、LRU 3/5、CR 3/5；context 级 `quality@1=25%`，`slo@1=100%`，
总消耗 1,060,806 tokens。该结果只标记为 `official-subset`，不是完整官方成绩。

### 5.4 可选：LoCoMo 与 LongMemEval-V2

- `https://github.com/snap-research/locomo` 适合慢速长对话记忆验证，作为后续补充，不阻塞首版。
- LongMemEval-V2 面向 agentic memory，先确认其官方 harness、许可和可复现性，
  再决定是否接入；不能拿旧版分数冒充 V2。

### 5.5 项目自有部分：记忆隔离与更新

新增内部任务族：

```text
evals/agent/cases/ab/memory-isolation/
evals/agent/cases/ab/memory-update/
```

首批 Case：

- 用户 A 的事实不能出现在用户 B 的回答中。
- 主会话与子 Agent 的记忆不能互相串线。
- 同一用户不同 session 的 scope 必须正确。
- 事实被新信息覆盖后，旧事实不能继续作为当前答案。
- 用户要求遗忘后，不能继续主动引用。
- 没有足够证据时，必须明确未知，禁止编造。
- 被压缩的历史仍可通过检索找回，但检索结果不能污染当前用户。

### 5.6 指标

| 指标 | 定义 |
|---|---|
| `official_accuracy` | 官方 evaluator 的任务正确率 |
| `retrieval_recall@k` | 目标记忆是否进入前 k 个结果 |
| `temporal_reasoning_accuracy` | 和时间顺序相关的正确率 |
| `knowledge_update_accuracy` | 新事实覆盖旧事实的正确率 |
| `abstention_accuracy` | 无证据时是否正确拒答 |
| `stale_memory_rate` | 继续引用过期事实的比例 |
| `cross_user_leak_rate` | 跨用户泄漏率，目标必须为 0 |
| `subagent_leak_rate` | 子 Agent 串线率，目标必须为 0 |
| `memory_write_precision` | 写入的耐久事实中有多少是正确的 |
| `retrieval_latency_ms` | 检索耗时 |
| `memory_token_overhead` | 记忆注入增加的总 token |
| `pass@1` / `pass^k` | 重复运行的稳定性 |

### 5.7 必要的控制组

每个记忆 Case 至少有一个 control：

- `no-memory-control`：不提供长期记忆，只允许当前会话。
- `oracle-context-control`：直接提供标注证据，作为上限。
- `full-memory`：使用真实 LTM / session_search。
- `stale-memory`：故意放入旧事实，检查是否会被错误更新。

没有 control 时，不能把“模型本来就知道”误判成“记忆系统有效”。

### 5.8 退出条件

- LongMemEval oracle 子集跑通，Adapter 不改官方数据和 scorer。
- `EvalMemorySeedCli` 能建立隔离的评测会话和 LTM。
- MemoryAgentBench 子集能按官方协议装载和评分。
- 跨用户、跨子 Agent 的泄漏率有负向测试。
- 记忆更新和遗忘有确定性 Oracle。
- 无证据时能区分“拒答正确”和“模型碰巧答错”。

## 6. 组合 C：缓存命中率与成本效率

### 6.1 先纠正一个概念

缓存命中率不是 Agent 能力排行榜，也没有统一的公开 Agent 缓存 Benchmark。

它是运营指标，回答：

- 同一会话中稳定前缀有没有被复用。
- 工具 schema、System Prompt、压缩是否导致缓存失效。
- 命中后是否真的降低了 prompt token、首 token 延迟和单任务成本。

可参考 `Don't Break the Cache`（arXiv:2602.03359）的工程评测方法，
但不能声称存在“官方缓存榜单”。

### 6.2 真实数据来源

只用 Execution Ledger 中的实际值：

- `promptTokens`
- `cachedPromptTokens`
- `completionTokens`
- `totalTokens`
- `firstTokenAt`
- `cacheStatus`
- `cacheRiskFlags`
- `systemFingerprint`
- `messageCount`
- `toolCount`

如果 provider 没有返回 `cachedPromptTokens`：

- 记 `unverified`。
- 不把 0 当作真实 miss。
- 不把本地 `cacheStatus=OK` 当作 provider 命中。

### 6.3 内部 Suite

新增：

```text
evals/agent/cases/ab/cache-efficiency/
```

任务类型：

1. 同一 System Prompt、同一工具目录、连续多轮短问题。
2. System Prompt 稳定，但每轮新增一个大工具结果。
3. 工具 schema 顺序变化，检查是否破坏前缀。
4. 工具描述不变但只改末尾用户消息。
5. 压缩前后，检查压缩是否让 prefix 失效。
6. 大前缀冷启动后，重复调用两到三次。
7. 小 prompt 对照，确认不把低于阈值的调用算成 miss。

### 6.4 A/B 设计

baseline 和 candidate 只改变一个变量：

- prompt 顺序。
- tool schema 排序。
- 压缩策略。
- 是否追加易变的系统提醒。
- 是否把工具结果放在稳定前缀之前。

模型、case、seed、预算、工具目录、重复次数保持一致。

### 6.5 指标

```text
eligible_calls = 排除每个新会话第一次调用和小 prompt 后的调用
token_cache_hit_rate = sum(cachedPromptTokens) / sum(promptTokens)
request_cache_hit_rate = count(cachedPromptTokens > 0) / eligible_calls
cold_start_miss_rate = cold_start_misses / new_sessions
cache_false_ok_rate = cacheStatus=OK 但 cachedPromptTokens=0 / 可观测调用
cache_risk_rate = cacheStatus=RISK / 可观测调用
cost_per_success = 总成本 / 成功任务数
ttft_delta_ms = 配对任务的 firstTokenMs 差值
```

报告必须同时给出：

- 命中率。
- 命中前后的绝对 token。
- 失败任务是否变多。
- 额外工具调用或额外轮次。
- 只有价格配置存在时才计算金额，否则只报 token 和延迟。

### 6.6 失效原因分类

固定分类，不能只写“缓存命中低”：

- `cold-start`
- `system-changed`
- `tools-changed`
- `messages-shrunk`
- `tool-result-volatile`
- `compaction-rewrite`
- `provider-unsupported`
- `usage-missing`
- `unknown`

### 6.7 退出条件

- 缓存指标全部来自 provider 实际 usage 或明确 `unverified`。
- 有稳定前缀、schema 变化、压缩失效三个对照。
- 报告能解释每一次主要 miss 的原因。
- 缓存改善不能以任务成功率下降为代价。
- 没有 provider 价格时，不伪造金额。

## 7. 与现有评测框架的集成方式

### 7.1 CompactBench

```text
CompactBench task
  -> CompactBenchAdapter.prepare()
  -> EvalCompactionCli
  -> real SessionContextCompactionService
  -> compacted transcript + context event
  -> official CompactBench scorer
```

Adapter 只做协议转换。官方题目、隐藏选项和 scorer 不进入项目仓库。

### 7.2 LongMemEval / MemoryAgentBench

```text
official task + history
  -> MemoryBenchmarkAdapter.prepare()
  -> EvalMemorySeedCli
  -> isolated eval session / LTM
  -> Java conversation runner
  -> trace + official answer
  -> official evaluator
```

历史装载必须通过 Java 正式写入路径。Python 只发送数据文件，不直连数据库。

### 7.3 Cache Suite

```text
internal case
  -> existing Java runner
  -> existing Ledger export
  -> cache metrics from llmInvocations
  -> internal ScoreRecord
  -> A/B report
```

不需要新 Runner，也不需要第二套报告。

### 7.4 manifest

每个 Benchmark manifest 至少增加：

```yaml
name: longmemeval
version: <pinned-version>
upstream: <repo-or-dataset-url>
upstreamCommit: <sha-or-dataset-revision>
license: <checked>
split: official-subset
scoreLabel: official-subset
datasetHash: sha256:...
runnerMode: session-fixture
environment: isolated-eval-db
officialEvaluator: true
```

内部组合使用：

```yaml
scoreLabel: internal
officialEvaluator: false
```

## 8. 分阶段实施

### 阶段 9.0：基础契约与 P0 修复

目标：先让三类评测有可靠的数据基础。

范围：

- P0-1：把 16 个真实存在但未导出的 LLM 字段补到 `LlmInvocationView`、导出器和 schema。
- P0-2：补齐 `runUid`、`userIdHash`、子 Agent 三字段和工具产物计数。
- P0-3：把压缩事件投影到 `contextEvents[]`。
- P0-4：把 LTM fork 事件投影到 `memoryEvents[]`。
- P0-5：补齐 `conversation`、`environmentEvents`、`environmentSnapshots` 数组及来源状态。
- 增加 `contextEvents`、`memoryEvents` 的通用 schema。
- 修复压缩审计 JSON 截断、失败降级契约和摘要结构校验。
- 保持文本/图片/音频 token 明细 nullable，禁止把缺失值写成 0。
- 建立 `trace-fields.json` 字段注册表，记录来源、可空性、阶段和隐私级别。
- 不实现 HITL 等待时间、RAG 专项或第三方观测平台接入。

验收：

- 导出一条真实 Ledger，16 个 LLM 字段、run/user/subagent 字段可被 Python 读取。
- 压缩事件、LTM fork 事件、conversation 和环境数组按统一 schema 导出。
- `conversationSource`、`environmentSource`、`snapshotSource` 能区分空数据和来源不可用。
- 缺字段显示 `null`，不显示 0。
- 定向 Java 测试和 Python Trace schema 测试通过。

### 阶段 9.1：上下文压缩

目标：先证明“真实压缩后还能继续”，再谈官方榜单。

状态（2026-11-08）：最小闭环和内部 8 Case 全量真实运行均已完成。真实链路
使用 `group/deepseek`、`ai-agent-eval` MySQL 和生产
`SessionContextCompactionServiceImpl`，不是 fixture。

真实接入时修复了三个阻塞：

- `requestId` 不能超过 Execution Ledger 的 `VARCHAR(64)`，改为长度安全的
  case hash + suffix。
- 内部小 fixture 不足以跨过生产窗口阈值，`compressed` Case 显式
  `force=true`，只强制进入压缩流程，不改生产压缩算法。
- 压缩服务的 requestId 防递归规则会跳过任何包含 `-compact` 的请求；
  carry-forward 改用 `eval-carry-forward-*`，并把生产压缩审计事件持久化，
  使 `contextEvents[]` 能从 Execution Ledger 重建。

任务：

1. 实现 `EvalCompactionCli`。
2. 建立 6–10 个 `compaction-carry-forward` Case。
3. 增加 uncompressed control 和负样本。
4. 接入 CompactBench 最小 smoke。
5. 接 CompactBench 官方可用子集。
6. 输出压缩专项报告。

验收：

- 真实 Java 压缩器触发成功。
- 压缩事件可重建且 JSON 合法。
- 至少一个负样本能检测到虚假记忆。
- 报告包含 compression ratio、carry-forward、重复询问和 token 回收。

已完成证据（2026-11-08 中性历史修正后）：

- Java 定向测试：`EvalCompactionCliTest`、`SessionContextCompactionOutcomeTest`、
  `EvalCompactionCarryForwardCliTest` 通过；Python compaction / Trace /
  experiment 定向测试通过。
- CompactBench `starter@2.0.1` 官方最小子集完成 8 个 Case；结果文件位于
  `D:\cute-evals-data\compactbench\starter-smoke2-results.jsonl`。
- `compaction-carry-forward` 已有 8 个 Case、真实 compression group、
  uncompressed control 和 false-memory 等负样本。
- 摘要结构修复后，7/7 压缩 Case 全部得到真实 `full-llm+drop-oldest`，
  不再降级 `local-fallback`；Goal / 约束 / 下一步续航质量 `7/7`。
- 首轮 neutral 全量（`compaction-carry-forward-full-20261108-8-neutral`）：
  strict `6/8`、quality `8/8`、SLO `6/8`、`pass@1=75%`；control 干净。
- 修正的两个测试构造缺陷：
  1. 旧 `historyPadding` 被同一句对抗性提示重复灌入 10 次，会诱导模型忽略
     压缩摘要并反复 `session_search`；已改为单份中性历史归档，只保留材料
     状态、已完成动作和指针，不再重复“只以正式用户消息为准”这类指令。
  2. 所有压缩 Case 复用同一个 `eval-user`，`session_search` 会把此前评测
     会话也检索回来造成串扰；`EvalCompactionCarryForwardCli` 现在每次运行
     生成独立 `eval-user-<suffix>`，用确定性测试锁定隔离契约。
- 长历史原先放在最后一条 user 前面，`protectLastN=2` 会把整段历史原样
  保护在 tail，导致“原文 + 摘要”同时存在；已把长历史移出保护尾部。
  修正后最终全量（`...-15-final`）的 7/7 压缩 Case 均为真实 `full-llm`，
  before `8,757`、after `2,900`、median compression ratio `3.01x`、
  `reclaimedTokens=+5,857`、`expandedCaseCount=0`。
- `...-15-final` 的历史结果仍为 strict `6/8`、quality `7/8`、SLO `7/8`；
  该结果暴露了两个真实问题，不能通过改 Oracle 或只调预算掩盖。
- `compressed-goal-constraints` 的修复：在最终 handoff 完成 wrap 后再做一次
  确定性标识保底扫描，写入
  `- Preserve these literal identifiers verbatim: ...`，并用
  `finalHandoffPreservesLiteralIdentifiersAfterWrapping` 锁定包装后路径。
  这解决的是“摘要生成时有标识、但最终 reinject 文本丢失”的路径问题，
  不是把自然语言答案改写成标准答案。
- `pointer-recovery` 的修复：Case 明确要求只调用一次 `session_search`，
  `strictToolSequence: true`、`maxToolCalls: 1`；`maxTotalTokens` 按
  两回合最小必要成本校准为 `32,000`，不再使用结构上不可能成立的 `24,000`。
  这是一条“最小路线”约束，不能退化成“多搜几次也算通过”。
- 修复后的定向全量（`...-16-followup`）：8 个 Case 无失败，
  deterministic 结果为 7 passed / 1 unverified；未验证项仅因为
  `agentevals` 或 judge 未配置，不是任务失败。`compressed-goal-constraints`
  已恢复通过，`pointer-recovery` 为两次 LLM 回合、一次
  `session_search`、`26,579` tokens，低于校准后的 `32,000`。
  7/7 压缩 Case 仍为真实 `full-llm`，before `8,757`、after `3,328`、
  median ratio `3.00x`、`reclaimedTokens=+5,429`、`expandedCaseCount=0`。
- `repeat=3` 结果（`...-14-source-identifiers-r3`）：24 次尝试中
  quality `7/8`、SLO `7/8`、`pass@1=75%`、`pass^k=75%`，压缩策略
  7/7 为 full-LLM，median ratio `2.97x`、`reclaimedTokens=+5,880`、
  `expandedCaseCount=0`。相比修正前，压缩已经从“越压越大”变成稳定回收。
- 约束标识符窗口误判修复：`CONSTRAINT-NO-DELETE` 后面的“任何情况下都不能
  删除原始数据”原先落入负向上下文窗口，导致这个正向约束被过滤。现在负向
  判断以冒号、逗号、分号、句号或换行为子句边界，不再把约束正文里的“不能”
  误算到标识符本身；`不得编造 ACTIVE-FAKE` 等负向用法仍会被排除。
  `constraintIdentifierIsNotFilteredByItsProhibitiveText` 已锁定该回归。
- 框架启用前的 `repeat=3` 基线
  （`compaction-carry-forward-full-20261108-22-constraint-fix-r3`）：
  8 个内部 Case、24 次真实模型尝试，`7 passed / 0 failed / 0 unstable /
  1 unverified`，`pass@1=87.50%`、`pass^k=87.50%`、quality
  `87.50%`、SLO `100%`。`compressed-goal-constraints` 为 3/3，三次的
  摘要和最终答复都包含 `GOAL-ALPHA`、`CONSTRAINT-NO-DELETE`、
  `NEXT-STEP-REPORT`。唯一 `unverified` 是 `pointer-recovery`：
  确定性结果 3/3 通过，但其可选 `agentevals` / `deepeval` 评分器未安装或
  judge 未配置，因此不能写成 verified passed。
- 该中间基线的压缩事实：21 次真实压缩尝试中 20 次 `full-llm`、1 次
  `local-fallback`；before `26,271` tokens、after `9,429` tokens、
  median ratio `2.94x`、reclaimed `16,842` tokens、expanded `0`。
  report 的代表性 7 个压缩 Case 汇总为 before `8,757`、after `2,925`、
  median ratio `2.95x`、reclaimed `5,832`；Cache 命中率 `95.29%`
  （27 次采样、0 unverified）。
  这些仍是项目内部 `internal` 结果，不是 CompactBench 官方分数；公开
  CompactBench 部分只能标 `official-subset`，不得与内部 Case 混算。
- AgentEvals 接入修复：strict 轨迹模式原先同时要求工具参数 `exact`，会把
  `session_search` 自动补上的 `scope=session` / `limit` 当成轨迹错误。
  现在保留工具名称、顺序和必需参数的 strict 校验，参数改为 `superset`：
  参考参数必须存在，允许 schema 的附加默认参数。最终答案仍由独立 Oracle
  判定，不能由轨迹匹配替代。
- 最终 `repeat=3` 基线
  （`compaction-carry-forward-full-20261108-23-frameworks-judge-r3`）：
  启用 `agentevals`、`deepeval` 和本地 `group/deepseek` judge 后，8 个
  内部 Case、24 次真实模型尝试全部
  `8 passed / 0 failed / 0 unstable / 0 unverified`，
  `pass@1=100%`、`pass^k=100%`、quality `100%`、SLO `100%`；
  Wilson 95% 置信区间为 `67.56%–100%`。118 个评分项中
  `0 unverified / 0 hard-gate failure`，另有 3 个 advisory judge 失败：
  `multi-fact` 与 `pointer-recovery` 的最终答复被 judge 认为过度解释，
  `pointer-recovery` 的一次 DeepEval 得分 `0.3`。它们不替代确定性 Oracle，
  但应作为“直接输出/简洁性”提示词问题继续观察，不能被当成零风险。
- 最终基线的全部 21 次压缩尝试：20 次 `full-llm`、1 次
  `local-fallback`；before `26,271`、after `9,368`、
  median ratio `2.97x`、reclaimed `16,903`、expanded `0`。
  report 的代表性压缩 Case 汇总为 before `8,757`、after `3,481`、
  reclaimed `5,276`；Cache 命中率 `95.76%`（27 次采样、
  0 unverified）。
- 报告已包含 compaction 汇总：触发数、carry-forward quality/strict、
  control clean、策略分布、full-LLM/fallback 数、median ratio、
  reclaimedTokens 和扩展 Case 数。

### 阶段 9.2：长期记忆

目标：证明记忆不是“当前会话碰巧记得”。

任务：

1. 实现 `EvalMemorySeedCli` 和隔离清理。
2. 接入 LongMemEval oracle 子集。
3. 接入 LongMemEval `_s` cleaned 子集。
4. [已完成] 接入 MemoryAgentBench 官方小 subset。
5. 建 `memory-isolation`、`memory-update` 内部 Case。
6. 跑跨用户、跨子 Agent、遗忘和过期事实测试。

验收：

- 每个记忆 Case 都有 `no-memory-control`。
- 跨用户和跨子 Agent 泄漏为 0。
- 官方 evaluator 分数与内部控制组分数分开。
- 历史数据不进入公开 Trace。

### 阶段 9.3：缓存与成本

目标：在正确率不降的前提下，验证 prompt cache 是否被正确利用。

任务：

1. 建 `cache-efficiency` 内部 Suite。
2. 实现缓存指标聚合和失效原因分类。
3. 增加 stable-prefix / schema-change / compaction 三组 A/B。
4. 在报告中加入 token、TTFT、成本（可选）。

验收：

- 所有指标来自实际 usage 或有 `unverified` 标记。
- 能识别至少 3 类缓存失效。
- 能证明一个优化在 token/延迟上有效，同时不降低任务成功率。

### 阶段 9.4：门禁与统一报告

目标：三条线进入同一套 CI 和报告，但不混成一个无法解释的总分。

PR：

- 只跑 deterministic Fixture。
- 压缩契约、缓存格式化、记忆隔离 schema。
- 目标 `<= 10 分钟`。

Nightly：

- 真实模型。
- 重复 3 次。
- CompactBench 小集、LongMemEval oracle 子集、MemoryAgentBench 小集、cache A/B。
- 输出 `pass@1`、`pass^k`、token、延迟、缓存指标和失败归属。

Release：

- 公开 Benchmark 的稳定 split 全量或明确 `official-subset`。
- 压缩和记忆的争议 Case 扩展到 6 次。
- 人工复核跨用户泄漏、虚假记忆、压缩丢状态和高成本运行。

### 阶段 9.5：全方位能力地图

三条线稳定后，再扩成全方位矩阵：

| 能力域 | 已有或计划 Benchmark |
|---|---|
| 通用多步推理 | GAIA |
| 多轮工具与用户交互 | tau²-bench |
| SQL / 数据分析 | BIRD、Spider2 |
| RAG | MRAG / Ragas 内部集 |
| 编码与终端 | SWE-bench Lite、Terminal-Bench |
| 浏览器 | WebArena / BrowserGym |
| 安全 | AgentDojo |
| 连续上下文 | CompactBench + carry-forward |
| 长期记忆 | LongMemEval + MemoryAgentBench |
| 效率与缓存 | 内部 cache-efficiency |
| 产物与可恢复性 | 内部 Artifact / Resume Suite |

全方位能力只做“能力雷达”和分层报告，不做一个没有校准的综合总分。

## 9. 日常评测示例

### PR

```text
15 个 deterministic Case
  6 个 compaction contract
  4 个 cache fixture
  3 个 memory isolation
  2 个 resume / artifact

目标：10 分钟内完成，不跑真实模型，不跑重型 Benchmark
```

### Nightly

```text
CompactBench:        20 个官方 smoke/subset
LongMemEval:         50 个 oracle 子集
MemoryAgentBench:    20 个官方 subset
Cache A/B:           12 对稳定/易变前缀任务
重复:                3 次
```

输出：

- 质量：官方分数、内部 control 分、hard gate。
- 续航：压缩后继续成功率、重复询问率、虚假记忆率。
- 记忆：跨用户泄漏、过期事实、检索召回。
- 效率：token、TTFT、cache hit、单任务成本。

### Release

- 选中的公开 split 全量或 `official-subset`。
- 三个能力域各至少一个稳定 baseline。
- 对争议 Cell 扩展到 6 次。
- 所有 `unverified`、跨用户泄漏、虚假记忆和压缩丢状态进入人工复核。

## 10. 报告样例

```text
Suite: continuity-memory-cache
Profile: nightly
Base: origin/main
Candidate: HEAD
Model: group/deepseek
Seed: 42
Repetitions: 3

Track         Benchmark / Suite          ScoreLabel       n    Metric
Compaction    CompactBench smoke         official-subset  20   score 0.78
Compaction    carry-forward              internal         12   pass@1 0.83
Memory        LongMemEval oracle         official-subset  50   accuracy 0.72
Memory        memory-isolation           internal         12   leak rate 0.00
Cache         stable-prefix A/B          internal         12   token hit 0.61
Cache         schema-change A/B          internal         12   token hit 0.34

Decision: needs-more-runs
Reasons:
- carry-forward 有两个 Case 在第二次重复后丢失 Active State。
- cache 改善主要来自 cold-start 排除规则，尚不能证明稳定前缀收益。
```

报告必须能下钻到：

- 具体 Case。
- 压缩前后计数。
- LLM invocation。
- 工具调用。
- memory event。
- cache miss 原因。
- trace 和原始 transcript 的相对路径。

## 11. 交付物

### 文档

- 本计划。
- `trace-fields.json` 字段注册表和字段来源说明。
- 三个能力的指标定义。
- 新增 Case 的编写指南。
- 公开 Benchmark 接入记录。
- 失败分类和人工复核手册。

### Java

- P0-1：`LlmInvocationView` 补齐 16 个 LLM 字段。
- P0-1：Trace 导出字段补全，缺失值为 `null`。
- P0-2：Run、用户 hash、子 Agent 和工具产物计数字段补全。
- P0-3：压缩事件读取和 `contextEvents[]` 投影。
- P0-4：LTM fork 事件读取和 `memoryEvents[]` 投影。
- P0-5：`conversation`、`environmentEvents`、`environmentSnapshots` 导出与来源状态。
- `EvalCompactionCli`。
- `EvalMemorySeedCli`。
- 压缩审计修复。
- 记忆 scope / owner 负向测试。

### Python

- `compactbench/` Adapter 和 manifest。
- `longmemeval/` Adapter 和 manifest。
- `memoryagentbench/` Adapter 和 manifest。
- `cache-efficiency` 内部 Suite。
- context / memory / cache Scorer。
- report 分层和指标聚合。
- `trace-fields.json` 字段注册表读取和字段缺失/零值区分测试。

### Cases

- `compaction-carry-forward`
- `memory-isolation`
- `memory-update`
- `cache-efficiency`

## 12. 风险与应对

| 风险 | 后果 | 应对 |
|---|---|---|
| 把摘要变短当成压缩成功 | 任务续航下降却被误判通过 | 真实 carry-forward + uncompressed control |
| 压缩失败后丢上下文 | 用户任务无法继续 | 审计合法 JSON、显式降级、负向测试 |
| 长期记忆越权 | 跨用户泄漏 | owner/scope 检查，泄漏率为硬门禁 |
| 模型本来就知道答案 | 误判记忆系统有效 | 必须有 no-memory control |
| 缓存状态是本地猜测 | 虚报命中 | 只用 provider `cachedPromptTokens`，缺失即 unverified |
| 缓存优化牺牲正确率 | 省 token 但任务失败 | 缓存指标和质量指标同时门禁 |
| 公开 Benchmark 被改动 | 分数不可对外比较 | Adapter-only，数据只读，固定 revision |
| 重型 Benchmark 拖垮 CI | PR 不稳定 | PR 只跑 deterministic，公开全量只进 Release |
| 把多条能力压成一个总分 | 无法定位问题 | 分 Track / Suite / ScoreLabel 报告 |

## 13. 明确不做

- 不修改 CompactBench、LongMemEval、MemoryAgentBench 或 LoCoMo 的官方数据。
- 不把内部 `compaction-recall` 冒充 CompactBench 官方成绩。
- 不让 Python 直接连接产品数据库。
- 不把 SSE 作为评测事实源。
- 不把缓存命中率当作任务正确率。
- 不在 PR 跑公开全量、Docker、VM 或真实长会话。
- 不在阶段 9.0–9.5 默认实现 HITL 等待时间或 RAG 专项运行器；
  P1 只预留字段契约，RAG 实际运行器由后续专项接入。
- 不接入 OpenTelemetry、OpenInference、Langfuse、Phoenix、Jaeger、Tempo
  或其他第三方观测平台。
- 不为每条评测线单独造 Runner 和报告系统。

## 14. 推荐执行顺序

```text
1. P0-1 16 个 LLM 字段补全
2. P0-2 Run / 用户 / 子 Agent 字段补全
3. contextEvents / memoryEvents schema
4. P0-3 压缩事件导出
5. P0-4 LTM fork 事件导出
6. P0-5 conversation / environment 数组导出
7. trace-fields 注册表与兼容测试
8. 压缩审计与失败降级修复
9. compaction-carry-forward 内部 Case
10. EvalCompactionCli
11. CompactBench smoke -> 子集
12. EvalMemorySeedCli（已完成）
13. LongMemEval oracle（已完成）；cleaned 全量（后续）
14. MemoryAgentBench subset（已完成）
15. memory-isolation / memory-update（已完成）
16. cache-efficiency Suite（已完成）
17. stable-prefix / schema-change / compaction A/B（已完成）
18. PR / Nightly / Release 门禁（已完成）
19. 全方位能力矩阵
```

完成 1–18 后，三个组合才算真正接入现有评测框架；第 19 步只是扩展能力地图，
不是首版的阻塞项。
