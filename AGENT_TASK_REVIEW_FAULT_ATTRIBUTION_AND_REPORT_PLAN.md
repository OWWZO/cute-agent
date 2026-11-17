# Agent 任务复盘：错误归因与综合报告开发计划

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档状态 | 待实施 |
| 适用产品 | Cute Agent 单次任务复盘 / Task Review |
| 主要目标 | 增加结构化错误归因、扩展评测维度、生成开发者可读的综合复盘报告 |
| 报告定位 | Advisory，仅用于开发分析和产品改进，不进入官方分数、不参与发布门禁 |
| 当前基线 | `task-review.v1` 六个质量维度、快速体检、AI 深度复盘、执行时间线 |
| 关联文档 | `AGENT_TASK_REVIEW_PLAN.md`、`AGENT_LLM_JUDGE_OPTIMIZATION_PLAN.md` |

---

## 2. 一句话结论

本次增强不是简单再增加几个 0-5 分，而是新增一层“结构化诊断”：

- 对每一次失败的工具调用给出责任层、原因、恢复状态和证据。
- 判断 Agent 自己是否诊断正确。
- 判断指令约束、上下文状态、规划调整和停止时机。
- 在确定性事实和已验证 Judge 结果之上，再生成一份开发者可读的完整执行报告。

最终目标不是告诉开发者“这个任务只有 2 分”，而是回答：

> 这个 Agent 做了什么，为什么这样做，哪里失败了，失败到底是谁的问题，它有没有发现并恢复，最终结果是否可信，下一次应该改模型、Prompt、工具还是 Harness。

---

## 3. 当前能力与缺口

### 3.1 当前已有能力

当前任务复盘已经具备：

1. 当前 Request 范围内的会话隔离。
2. Execution Ledger Trace 导出。
3. 工具调用时间线、入参、结果、状态和耗时。
4. 助手可见回复。
5. 最终回答。
6. 确定性指标：
   - 执行状态
   - 总耗时
   - Token
   - 工具调用数
   - 工具失败数
   - 重复调用数
   - 风险分
7. AI Judge 六个维度：
   - 任务完成度
   - 工具选择
   - 错误恢复
   - 执行效率
   - 结果验证
   - 回答诚实性
8. Evidence 引用校验。
9. 前端任务复盘页。

### 3.2 当前主要缺口

#### 缺口一：失败只有现象，没有责任层

现在只记录：

- 工具失败了。
- 工具后来有没有恢复。

但没有区分：

- 模型选错工具。
- 模型把工具选对了，但参数写错。
- 工具描述或 Schema 本身有歧义。
- 工具执行器自身崩溃。
- 环境、登录或网络失败。
- 用户主动停止。
- Trace 缺少证据，无法判断。

#### 缺口二：工具选择与参数正确性混在一起

“工具选得对”不等于“参数写得对”。

例如：

- 选择 `browser` 是合理的。
- 但传入不存在的 selector，属于参数错误。
- 如果工具没有提供 selector 规则或错误信息不可读，则同时存在 Harness 合约问题。

当前 `tool_selection` 无法表达这种差异。

#### 缺口三：没有判断 Agent 自己的诊断是否正确

Agent 可能：

- 把参数错误误判成网页问题。
- 把 Harness 异常误判成模型能力问题。
- 把用户停止误判成工具失败。
- 看到错误后仍然重复相同调用。

这与“实际根因”是两件事，应单独评估。

#### 缺口四：上下文与多轮状态跟踪不足

当前已经修复过“最终回复误取上一轮”的具体 Bug，但还没有一个稳定维度持续评估：

- 是否正确区分当前 Request 与历史 Request。
- 是否记住用户上一轮纠正。
- 用户改变目标后是否停止旧计划。
- 是否重复读取已经拿到的信息。

#### 缺口五：停止时机没有单独评估

需要区分：

- 已完成但继续做低价值调用。
- 证据不足就提前结束。
- 一次工具失败就过早放弃。
- 用户明确停止后仍尝试继续。

#### 缺口六：没有综合长报告

当前 `quality.summary` 只有一段摘要，无法完整说明：

- 整条执行故事。
- 每个阶段用了什么工具。
- 失败发生在哪一步。
- 根本原因是什么。
- 是否恢复。
- 哪条替代路径可能更好。
- 开发者应该改什么。

---

## 4. 目标与非目标

### 4.1 目标

#### 目标 A：结构化错误归因

每次失败的工具调用输出：

- 失败阶段。
- 失败现象。
- 责任层。
- 贡献责任层。
- 是否恢复。
- 恢复方式。
- 置信度。
- 证据引用。

#### 目标 B：扩展质量维度

新增或强化：

- 工具参数正确性。
- Agent 自身诊断质量。
- 指令与约束遵循度。
- 上下文与状态跟踪。
- 规划与策略调整。
- 停止时机。
- 产物质量。

#### 目标 C：生成综合复盘报告

报告需要覆盖整个执行过程，包括：

- Agent 如何理解任务。
- 使用了哪些工具。
- 执行分为哪些阶段。
- 每阶段达成了什么。
- 出现了哪些错误。
- 错误归因是什么。
- Agent 如何应对。
- 最终结果是什么。
- 哪些内容已验证。
- 哪些内容未验证。
- 是否存在更直接或更稳妥的路径。
- 对模型、Prompt、工具、Harness 的具体改进建议。

#### 目标 D：结论可追溯

所有重要结论必须关联：

- `toolCallId`
- `messageId`
- 步骤编号
- Evidence quote

没有证据时输出 `UNKNOWN`，不允许硬猜。

### 4.2 非目标

- 不把错误归因加入官方 benchmark 分数。
- 不把长报告作为发布门禁。
- 不读取模型隐藏的 chain-of-thought。
- 不让 Judge 覆盖确定性工具失败事实。
- 不根据单个失败直接断定“模型差”或“Harness 差”。
- 不允许自由格式 HTML 直接进入前端。
- 不在本阶段实现自动修复代码。
- 不在本阶段训练或微调模型。

---

## 5. 设计原则

### 5.1 事实、归因、建议三层分离

#### 第一层：事实

只来自 Trace 和确定性规则：

- 工具名。
- 入参。
- 结果。
- 状态。
- 耗时。
- 是否停止。
- 是否重复调用。
- 是否恢复。

#### 第二层：归因

由确定性规则和 Judge 共同判断：

- 哪个责任层。
- 为什么。
- 置信度。
- 是否存在多个贡献原因。

#### 第三层：建议

面向开发者的改进建议：

- 改 Prompt。
- 改模型策略。
- 改工具描述。
- 改参数 Schema。
- 改 Harness。
- 改环境配置。
- 增加重试或回退。

建议不得反向修改事实。

### 5.2 确定性优先

以下判断优先由规则完成：

- JSON 是否可解析。
- 必填参数是否缺失。
- 参数类型是否正确。
- HTTP 状态码。
- 超时。
- 用户停止。
- 工具状态。
- 是否有结果。
- 是否重复同一调用。
- 是否有后续成功调用。

Judge 只负责：

- 参数语义是否符合用户目标。
- 工具选择是否合理。
- Agent 是否误读错误。
- 是否存在更简单的策略。
- 失败责任是否存在 Harness 合约因素。

### 5.3 结构化归因不做单一分数

`failureAttribution` 不是 0-5 分，而是每条失败的分类账。

可以另外提供：

- 模型决策问题次数。
- 模型参数问题次数。
- Harness 合约问题次数。
- Harness 运行问题次数。
- 环境问题次数。
- 用户操作次数。
- Trace 缺口次数。
- 无法判断次数。

### 5.4 允许 `UNKNOWN`

如果证据不足：

- 不强行归因。
- 输出 `UNKNOWN`。
- 在报告中说明缺少什么证据。
- 不把 `UNKNOWN` 当作模型失败或 Harness 失败。

### 5.5 报告不得夸大

报告只能使用：

- 确定性事实。
- 已验证 Judge 结论。
- 明确标记为建议的推断。

禁止出现：

- “绝对最优路径”。
- “模型一定有问题”。
- “工具一定有 Bug”。
- 没有证据支撑的根因。

---

## 6. 完整使用流程

### 6.1 用户在前端发起复盘

保持现有流程：

1. 用户完成一轮会话。
2. 点击“复盘这次任务”。
3. 选择当前 Request。
4. 请求 Python 评测服务。
5. Java 导出当前 Request Trace。

### 6.2 快速体检

1-3 秒内返回：

- 任务结论。
- 耗时。
- Token。
- 工具调用次数。
- 失败次数。
- 重复调用。
- 风险项。
- 初步失败账本。

### 6.3 结构化诊断

在快速事实基础上生成：

- 失败归因。
- 参数正确性。
- 恢复情况。
- 上下文和停止时机候选问题。

### 6.4 AI 深度复盘

Judge 输出：

- 质量维度评分。
- 失败归因修正或补充。
- Agent 自身诊断质量。
- 改进建议。

### 6.5 综合报告

第二个报告生成阶段输出：

- 执行故事。
- 工具地图。
- 失败时间线。
- 责任分布。
- 更优路径建议。
- 开发者行动项。

### 6.6 前端展示

结果页新增：

- “综合复盘报告”主面板。
- “错误归因”分类账。
- “责任分布”统计。
- “工具参数问题”列表。
- “Agent 诊断质量”。
- “改进建议”按模型、Prompt、工具、Harness、环境分组。

---

## 7. 总体架构

```text
Java Execution Ledger
        |
        v
Task Review Trace Export
        |
        v
Process View + Deterministic Facts
        |
        +-----------------------------+
        |                             |
        v                             v
Diagnostic Engine              Judge Pass 1
规则型失败账本                  质量评分 + 归因补强
        |                             |
        +-------------+---------------+
                      |
                      v
             Verified Review Facts
                      |
                      v
               Narrative Report Pass
                      |
                      v
               JSON + Markdown
                      |
                      v
                Task Review UI
```

### 7.1 为什么拆成两个 Judge 阶段

第一阶段：

- 关注评分。
- 关注结构化归因。
- 严格 JSON Schema。
- 每个结论必须有 Evidence。

第二阶段：

- 关注叙事。
- 不重新评分。
- 只能使用第一阶段已验证事实。
- 输出结构化章节。

这样可以避免：

- 长报告生成时修改评分。
- 评分 JSON 过大导致解析失败。
- 叙事文本中出现无证据根因。

---

## 8. 数据契约

### 8.1 FailureLayer

新增枚举：

```text
MODEL_DECISION
MODEL_ARGUMENT
HARNESS_CONTRACT
HARNESS_RUNTIME
ENVIRONMENT
USER_ACTION
TRACE_GAP
UNKNOWN
```

含义：

| 枚举 | 含义 |
| --- | --- |
| `MODEL_DECISION` | 工具选错、顺序错了、重复调用、无视已有结果、过早结束 |
| `MODEL_ARGUMENT` | 工具选择合理，但参数格式、类型、选择器、URL、路径、JSON 或语义错误 |
| `HARNESS_CONTRACT` | 工具描述、Schema、必填项、返回格式或错误信息设计不合理 |
| `HARNESS_RUNTIME` | 工具执行器崩溃、超时、结果丢失、状态误报、序列化失败 |
| `ENVIRONMENT` | 登录失效、网页变化、网络失败、远端沙箱不可用 |
| `USER_ACTION` | 用户主动停止、取消或修改目标 |
| `TRACE_GAP` | Trace 本身缺少判断所需证据 |
| `UNKNOWN` | 有证据但仍无法可靠归因 |

### 8.2 FailureAttribution

新增结构：

```json
{
  "attributionId": "attr-001",
  "requestId": "1794819108266-9502",
  "runId": "1794819108266-9502",
  "toolCallId": "call_xxx",
  "step": 18,
  "toolName": "browser",
  "toolStatus": "FAILED",
  "failureKind": "INVALID_SELECTOR",
  "primaryLayer": "MODEL_ARGUMENT",
  "contributingLayers": [
    "HARNESS_CONTRACT"
  ],
  "reason": "模型使用了页面上不存在的选择器；同时工具返回了底层异常，没有指出合法选择器格式。",
  "recovered": true,
  "recoverySummary": "后续重新观察页面并使用有效选择器成功。",
  "confidence": 0.86,
  "source": "deterministic+judge",
  "ruleIds": [
    "ARG_SELECTOR_NOT_FOUND",
    "HARNESS_ERROR_NOT_ACTIONABLE"
  ],
  "evidenceRefs": [
    "tool:call_xxx",
    "message:148479",
    "tool:call_yyy"
  ],
  "evidence": []
}
```

字段要求：

- `toolCallId` 必须存在。
- `requestId` 必须存在。
- `step` 使用 Process View 的稳定步骤编号。
- `primaryLayer` 必须唯一。
- `contributingLayers` 可以有多个。
- `recovered` 只表示后续有证据表明恢复，不表示失败被忽略。
- `confidence` 范围 0-1。
- 所有 `evidenceRefs` 必须能在当前 trace 中解析。

### 8.3 DiagnosticSummary

```json
{
  "totalFailures": 8,
  "attributedFailures": 7,
  "unattributedFailures": 1,
  "layerCounts": {
    "MODEL_DECISION": 3,
    "MODEL_ARGUMENT": 4,
    "HARNESS_CONTRACT": 1,
    "HARNESS_RUNTIME": 0,
    "ENVIRONMENT": 2,
    "USER_ACTION": 1,
    "TRACE_GAP": 0,
    "UNKNOWN": 1
  },
  "recoveredCount": 5,
  "unrecoveredCount": 3,
  "parameterIssueCount": 4,
  "diagnosisMismatchCount": 2
}
```

注意：

- 一次失败可以有多个贡献层。
- 汇总次数按 `primaryLayer` 统计。
- 贡献层单独统计，不混入主责任次数。

### 8.4 新增质量维度

建议新增 `task-review.v2`，保留 `task-review.v1` 作为兼容版本。

#### 核心结果维度

| id | 说明 | 权重 |
| --- | --- | --- |
| `task_completion` | 是否完成用户真正想要的结果 | 2.0 |
| `constraint_adherence` | 是否遵守明确数量和操作约束 | 1.5 |
| `answer_honesty` | 是否如实描述完成、失败和未验证内容 | 1.0 |

#### 执行过程维度

| id | 说明 | 权重 |
| --- | --- | --- |
| `tool_selection` | 工具类型和调用顺序是否合理 | 1.0 |
| `tool_argument_correctness` | 工具参数是否符合 Schema 和用户意图 | 1.0 |
| `error_recovery` | 失败后是否换策略并恢复 | 1.0 |
| `diagnosis_quality` | Agent 是否诊断出真正原因 | 1.0 |
| `planning_adaptation` | 是否根据证据调整计划 | 1.0 |
| `context_tracking` | 是否正确跟踪当前轮、历史轮和状态 | 1.0 |
| `stopping_discipline` | 是否在正确时机停止 | 1.0 |

#### 结果验证维度

| id | 说明 | 权重 |
| --- | --- | --- |
| `result_verification` | 声称完成前是否检查证据或产物 | 1.0 |
| `artifact_quality` | 产物是否正确、完整、相关 | 1.0 |

#### 成本维度

| id | 说明 | 权重 |
| --- | --- | --- |
| `execution_efficiency` | Token、时间和工具调用是否合理 | 0.5 |

`artifact_quality` 仅在存在文件或报告产物时启用。

`context_tracking` 在多轮会话、多 Request 或存在用户纠正时启用。

### 8.5 DimensionVerdict 扩展

建议增加：

```json
{
  "id": "tool_argument_correctness",
  "label": "工具参数正确性",
  "applicable": true,
  "score": 2,
  "level": "low",
  "passed": false,
  "reason": "两次 browser 调用使用不存在的选择器。",
  "evidence": []
}
```

新增字段：

- `applicable`：当前任务是否适用。
- `notApplicableReason`：不适用原因。
- `diagnosticRefs`：关联的失败归因 ID。

不适用维度：

- 不计入加权分。
- 不显示为失败。
- 显示“本次不适用”。

---

## 9. Evidence Packet 扩展

当前证据包已经包含：

- 当前 Request 会话。
- 助手可见中间回复。
- 工具名。
- 工具入参。
- 工具结果。
- 工具状态。
- 最终回答。
- 确定性指标。

本次需要补充以下内容。

### 9.1 Event Sequence

新增规范化事件序列：

```json
{
  "events": [
    {
      "seq": 1,
      "kind": "assistant_message",
      "messageId": "148437",
      "content": "..."
    },
    {
      "seq": 2,
      "kind": "tool_call",
      "toolCallId": "call_xxx",
      "toolName": "browser",
      "step": 1,
      "status": "FAILED",
      "errorCode": "INVALID_SELECTOR"
    },
    {
      "seq": 3,
      "kind": "tool_result",
      "toolCallId": "call_xxx",
      "status": "FAILED",
      "content": "..."
    }
  ]
}
```

目的：

- 不再让 Judge 自己拼接 conversation 和 toolCalls。
- 降低步骤顺序误判。
- 为报告生成提供稳定叙事顺序。

### 9.2 Tool Contract Snapshot

新增工具契约快照：

```json
{
  "toolName": "browser",
  "version": "1.0",
  "description": "...",
  "inputSchema": {},
  "required": [],
  "examples": [],
  "sideEffectLevel": "read",
  "schemaHash": "sha256:..."
}
```

来源：

- Java 工具注册表。
- Trace 中的 `tools`。
- 当前运行时的工具 Schema。

这样 Judge 才能判断：

- 是模型参数不符合 Schema。
- 还是 Schema 本身没有表达清楚。
- 还是工具描述和实际行为不一致。

### 9.3 Tool Invocation Facts

在现有工具结果上增加：

- `errorCode`
- `errorClass`
- `retryCount`
- `requestedAt`
- `startedAt`
- `finishedAt`
- `httpStatus`
- `timeoutMs`
- `cancelledBy`
- `inputParseError`
- `inputValidationErrors`
- `resultPresent`
- `statusSource`

这些字段应优先来自确定性记录，不允许 Judge 推测。

### 9.4 Artifact Inventory

新增产物清单：

- 文件路径。
- 文件类型。
- 文件大小。
- Hash。
- 是否可读。
- 文本预览。
- 是否在最终工作区中存在。

当前 `run_task_review_judge` 传的是 `workspace=None`，因此产物质量无法评估。

后续需要二选一：

1. Java 导出受控产物预览。
2. Python 通过只读工作区映射读取产物。

优先选择 Java 导出，避免 Python 直接访问用户工作区。

### 9.5 Evidence Budget

当前配置：

- 总预算约 120,000 字符。
- 单个工具结果约 12,000 字符。

本次需要改为“关键证据优先”：

1. 用户目标和明确约束。
2. 失败工具名、状态、错误码、错误消息。
3. 失败工具入参。
4. 失败后的恢复调用。
5. 最终回答。
6. 关键助手可见回复。
7. 成功工具结果。
8. 其他普通对话。

失败证据不得因为前面的普通日志过长而被全部截掉。

### 9.6 隐私与脱敏

继续保持：

- 不传 Authorization。
- 不传 Cookie。
- 不传 API Key。
- 不传密码。
- 对工具结果脱敏。
- 对文件内容脱敏。

新增：

- 工具 Schema 中的默认密钥值必须脱敏。
- 环境变量快照不进入 Judge。
- 用户个人信息仅在必要证据中出现，并优先截断。

---

## 10. 确定性诊断引擎

### 10.1 新增模块

建议新增：

```text
evals/agent/src/cute_evals/task_review/diagnostics.py
evals/agent/src/cute_evals/task_review/failure_rules.py
evals/agent/src/cute_evals/task_review/argument_checks.py
evals/agent/src/cute_evals/task_review/recovery.py
```

### 10.2 处理顺序

```text
1. 读取工具调用
2. 解析工具入参
3. 校验 JSON
4. 校验必填字段
5. 校验字段类型
6. 校验选择器、URL、路径
7. 读取工具状态和错误码
8. 标记用户停止
9. 标记 Trace 缺失
10. 查找恢复调用
11. 查找重复调用
12. 生成候选归因
13. 交给 Judge 处理歧义部分
```

### 10.3 工具参数正确性规则

#### `ARG_JSON_INVALID`

工具入参不是合法 JSON。

默认归因：

- `MODEL_ARGUMENT`

#### `ARG_REQUIRED_MISSING`

缺少 Schema 必填字段。

默认归因：

- `MODEL_ARGUMENT`

如果 Schema 与工具实现不一致：

- `primaryLayer = HARNESS_CONTRACT`
- `contributingLayers = [MODEL_ARGUMENT]`

#### `ARG_TYPE_MISMATCH`

参数类型不匹配。

默认归因：

- `MODEL_ARGUMENT`

#### `ARG_SELECTOR_NOT_FOUND`

选择器不存在。

默认归因：

- `MODEL_ARGUMENT`

如果工具没有提供稳定选择器或可读错误：

- 增加 `HARNESS_CONTRACT`

#### `ARG_PATH_NOT_FOUND`

路径不存在。

默认归因：

- `MODEL_ARGUMENT`

如果是远端工作区同步缺失：

- `ENVIRONMENT` 或 `HARNESS_RUNTIME`

#### `ARG_URL_INVALID`

URL 格式不合法或协议错误。

默认归因：

- `MODEL_ARGUMENT`

#### `ARG_REPEATED_AFTER_FAILURE`

失败后使用完全相同参数重复调用。

默认归因：

- `MODEL_DECISION`

### 10.4 失败状态规则

#### `FAIL_USER_STOP`

存在用户停止或取消事实。

归因：

- `USER_ACTION`

#### `FAIL_TIMEOUT`

工具超时。

归因：

- `HARNESS_RUNTIME`

如果调用参数本身会导致长时间阻塞：

- 增加 `MODEL_ARGUMENT`

#### `FAIL_NETWORK`

存在网络错误。

归因：

- `ENVIRONMENT`

#### `FAIL_AUTH`

登录、Token 或权限失效。

归因：

- `ENVIRONMENT`

#### `FAIL_EXECUTOR_EXCEPTION`

工具执行器内部异常。

归因：

- `HARNESS_RUNTIME`

#### `FAIL_RESULT_MISSING`

工具有调用但无结果、无状态或无错误信息。

归因：

- `TRACE_GAP`

如果运行时明确丢失结果：

- `HARNESS_RUNTIME`

#### `FAIL_SCHEMA_CONTRACT`

工具描述、输入 Schema 或返回结构与实际行为不一致。

归因：

- `HARNESS_CONTRACT`

### 10.5 恢复检测

对每个失败调用查找：

- 后续是否成功调用同一工具。
- 是否使用不同参数。
- 是否切换到替代工具。
- 是否读取错误信息后改变策略。
- 是否只是重复原调用。

输出：

```json
{
  "recovered": true,
  "recoveryType": "CHANGED_ARGUMENT",
  "recoverySummary": "重新观察页面后使用有效选择器成功。",
  "evidenceRefs": [
    "tool:call_xxx",
    "tool:call_yyy"
  ]
}
```

恢复类型：

- `RETRY_SAME_INPUT`
- `CHANGED_ARGUMENT`
- `CHANGED_TOOL`
- `USER_INTERVENTION`
- `NO_RECOVERY`
- `UNKNOWN`

### 10.6 重复调用检测

对以下内容做 Canonical JSON：

- 工具名。
- 参数。
- 目标对象。

标记：

- 相同输入连续失败。
- 相同输入无新结果。
- 相同搜索词重复调用。
- 同一文件重复读取。

重复本身不等于错误，需要结合上下文：

- 文件可能被更新。
- 页面可能已变化。
- 搜索结果可能刷新。

Judge 可解释合理重复，但确定性事实必须保留。

---

## 11. Judge Pass 1：结构化评分与归因

### 11.1 输入

输入包括：

- 用户目标。
- 明确验收标准。
- Event Sequence。
- 会话可见消息。
- 工具调用。
- 工具结果。
- 工具契约。
- 确定性候选归因。
- 产物清单。

### 11.2 输出

```json
{
  "summary": "...",
  "confidence": 0.86,
  "dimensions": [],
  "failureAttributions": [],
  "diagnosticSummary": {},
  "findings": [],
  "limitations": []
}
```

### 11.3 归因规则

Judge 必须：

- 优先接受确定性事实。
- 只有在有证据时才修改候选归因。
- 说明修改原因。
- 对每条归因引用 `toolCallId` 或 `messageId`。
- 对 Harness 问题引用工具描述、Schema 或运行时异常。
- 对模型问题引用模型入参、助手回复或恢复行为。
- 对未知情况输出 `UNKNOWN`。

Judge 不得：

- 仅因为报错发生在工具内部就归因 Harness。
- 仅因为工具调用失败就归因模型。
- 把用户停止归因为工具失败。
- 把 Trace 缺失归因为 Agent 能力。

### 11.4 diagnosis_quality 维度

评估 Agent 自己的诊断过程：

| 分数 | 标准 |
| --- | --- |
| 5 | 准确定位根因，并用正确策略恢复 |
| 4 | 基本定位正确，恢复略慢或有少量绕路 |
| 3 | 找到部分原因，但方案不完整 |
| 2 | 误判原因，经过多次试错才恢复 |
| 1 | 持续误判或盲目重复 |
| 0 | 无视错误并继续执行危险或无效操作 |

评估证据：

- 错误发生后的助手回复。
- 下一次工具调用的参数变化。
- 是否切换到正确替代工具。
- 是否读取了错误结果。

### 11.5 证据校验

沿用现有 `validate_evidence`，并增加：

- `failure_attribution` 证据类型。
- `tool_contract` 证据类型。
- `artifact` 证据类型。
- 所有 `evidenceRefs` 必须能解析。
- 归因原因必须至少有一条证据。

### 11.6 失败处理

如果 Judge 输出无效：

1. 保留确定性失败账本。
2. 保留快速体检结果。
3. Judge 状态标记为 `unverified`。
4. 显示具体校验错误。
5. 不阻止综合报告使用确定性事实。

综合报告可以有两种状态：

- `verified`：包含 Judge 评分和归因。
- `fact_only`：只包含确定性事实和有限建议。

---

## 12. Judge Pass 2：综合复盘报告

### 12.1 定位

Pass 2 不负责重新评分。

Pass 2 只负责把已验证事实组织成开发者可读的叙事。

### 12.2 输入

- 用户目标。
- 明确约束。
- Verified Facts。
- Failure Attributions。
- Dimension Verdicts。
- 关键工具调用。
- 关键助手回复。
- 最终回答。
- 产物清单。

不重新发送全部原始 Trace，避免：

- 成本过高。
- 上下文过长。
- 报告模型重新解释事实。

### 12.3 输出结构

```json
{
  "schemaVersion": "1.0",
  "status": "verified",
  "headline": "任务未完成，主要问题不在浏览器环境，而在发布流程验证不足。",
  "executiveSummary": [
    "...",
    "..."
  ],
  "taskUnderstanding": {
    "goal": "...",
    "constraints": [],
    "acceptanceCriteria": []
  },
  "executionNarrative": [
    {
      "phase": "验证发布链路",
      "summary": "...",
      "outcome": "partial",
      "evidenceRefs": []
    }
  ],
  "toolStory": [
    {
      "toolName": "browser",
      "purpose": "打开搜索页并验证回复链路",
      "calls": 6,
      "failures": 2,
      "assessment": "..."
    }
  ],
  "failureStory": [
    {
      "title": "选择器错误导致输入失败",
      "primaryLayer": "MODEL_ARGUMENT",
      "detail": "...",
      "recovered": true,
      "evidenceRefs": []
    }
  ],
  "whatWorked": [],
  "whatFailed": [],
  "betterApproach": [
    {
      "title": "先验证单条发布闭环",
      "detail": "...",
      "tradeoff": "..."
    }
  ],
  "developerActions": [
    {
      "target": "TOOL_CONTRACT",
      "priority": "P0",
      "action": "为 browser 工具补充选择器错误类型和可用选择器提示"
    }
  ],
  "verification": {
    "verified": [],
    "unverified": []
  },
  "limitations": [],
  "markdown": "..."
}
```

### 12.4 报告章节

#### 第一章：一句话结论

说明：

- 是否完成。
- 最关键的成功点。
- 最关键的问题。
- 主要责任层。

#### 第二章：任务理解与约束

列出：

- 用户真正想要什么。
- 明确数量。
- 明确禁止项。
- 验收标准。
- 不明确的地方。

#### 第三章：执行故事

按阶段描述：

1. 初始计划。
2. 第一次工具验证。
3. 遇到的问题。
4. 策略调整。
5. 批量执行。
6. 最终验证。
7. 最终停止原因。

每个阶段包含：

- 做了什么。
- 为什么做。
- 结果是什么。
- 证据是什么。

#### 第四章：工具使用地图

按工具汇总：

- 用了哪些工具。
- 每个工具做什么。
- 成功次数。
- 失败次数。
- 主要失败原因。
- 是否合理。
- 是否有替代工具。

#### 第五章：失败与责任归因

按失败列表展示：

- 现象。
- 主责任层。
- 贡献责任层。
- 原因。
- 是否恢复。
- 恢复方式。
- 证据。

#### 第六章：质量评分

展示：

- 任务完成度。
- 指令遵循。
- 工具选择。
- 参数正确性。
- 错误恢复。
- 诊断质量。
- 规划调整。
- 上下文跟踪。
- 停止时机。
- 结果验证。
- 回答诚实性。
- 产物质量。

#### 第七章：做对了什么

必须列出有证据支撑的优点，不能只写套话。

#### 第八章：哪里可以更好

提供替代路径：

- 更直接的工具。
- 更稳妥的顺序。
- 更早的验证。
- 更少的重复调用。
- 更清晰的停止条件。

#### 第九章：开发者行动项

按责任层分组：

- `MODEL_DECISION`：Prompt、策略、停止条件。
- `MODEL_ARGUMENT`：参数示例、Schema、校验提示。
- `HARNESS_CONTRACT`：工具描述、错误码、返回结构。
- `HARNESS_RUNTIME`：执行器、超时、恢复、日志。
- `ENVIRONMENT`：登录、网络、沙箱、页面变化。
- `TRACE_GAP`：补充观测数据。

每条行动项包含：

- 优先级。
- 目标层。
- 具体动作。
- 预期效果。
- 证据引用。

#### 第十章：验证与限制

明确说明：

- 哪些结果已验证。
- 哪些结果未验证。
- 哪些结论是推断。
- 哪些证据被截断。
- 哪些责任无法判断。

### 12.5 Markdown 渲染

Python 生成结构化 JSON 后，再生成 Markdown：

```text
quality/narrative.json
quality/narrative.md
```

前端不直接使用模型返回的 HTML。

Markdown 模板由代码生成，模型只填充字段。

---

## 13. 报告数据存储

### 13.1 目录结构

```text
~/.cute-evals/task-reviews/<jobId>/
  job.json
  request.json
  preflight.json
  trace/
    bundle.json
  diagnostics/
    facts.json
    failure-attributions.json
    argument-checks.json
  quality/
    judge.json
    narrative.json
    narrative.md
```

### 13.2 加载策略

- `job.json` 保存状态、快速体检、质量摘要和文件引用。
- 大报告不直接塞入 `job.json`。
- `GET /task-review/jobs/{jobId}` 读取并合并必要内容。
- 前端默认只加载摘要和当前可见章节。
- 需要完整报告时再加载 `narrative`。

### 13.3 兼容旧任务

旧任务没有：

- `failureAttributions`
- `diagnosticSummary`
- `narrative`

前端必须：

- 正常展示旧字段。
- 对缺失的新字段隐藏对应区块。
- 不把旧任务标记为失败。

---

## 14. 前端设计

保持现有苹果风视觉体系，不引入新的 UI 风格。

### 14.1 页面结构

建议顺序：

1. 总览卡。
2. 一句话结论。
3. 综合复盘报告。
4. 错误归因。
5. 质量评分。
6. 执行时间线。
7. 产物与验证。
8. 开发者行动项。

### 14.2 错误归因卡片

每条失败显示：

- 工具名。
- 步骤。
- 主责任层 Badge。
- 贡献责任层。
- 失败原因。
- 是否恢复。
- 证据展开。

层标签示例：

- `模型决策`
- `模型参数`
- `Harness 合约`
- `Harness 运行`
- `环境`
- `用户操作`
- `Trace 缺口`
- `无法判断`

颜色只作为辅助：

- 模型层使用蓝色系。
- Harness 层使用橙色系。
- 环境层使用灰色系。
- Trace 缺口使用中性色。
- 不把 `MODEL_*` 统一显示成红色，避免暗示模型一定错误。

### 14.3 综合报告面板

支持：

- 顶部目录。
- 章节折叠。
- 与时间线联动。
- 点击证据定位到对应工具调用。
- 复制 Markdown。

第一阶段只读，不提供在线编辑。

### 14.4 报告与时间线关系

报告不是重复时间线。

报告负责：

- 解释执行故事。
- 总结阶段结果。
- 归因和给建议。

时间线负责：

- 提供原始事实。
- 展示入参和结果。
- 作为报告证据落地页。

---

## 15. Java / Trace 导出改动

### 15.1 工具契约

Java 导出服务需要补充：

- 当前 Run 使用的工具列表。
- 工具描述。
- 输入 Schema。
- 工具版本。
- 工具副作用等级。

### 15.2 工具运行事实

优先补充：

- 错误码。
- 错误类型。
- 超时。
- 状态来源。
- 取消来源。
- 结果是否存在。
- 参数原始字符串是否可解析。

### 15.3 产物清单

如果本次任务产生文件：

- 文件路径。
- 大小。
- Hash。
- MIME。
- 文本预览。

只导出允许的文本类型。

### 15.4 安全

- 继续只导出当前 Request。
- 继续做脱敏。
- 不导出其他用户会话。
- 不导出隐藏推理。
- 不导出密钥和环境变量。

---

## 16. 后端 API 设计

不改变现有创建复盘任务 API 的语义。

### 16.1 Job 响应扩展

`GET /task-review/jobs/{jobId}` 新增：

```json
{
  "diagnostics": {
    "summary": {},
    "failureAttributions": []
  },
  "quality": {
    "narrativeStatus": "verified",
    "narrative": {}
  }
}
```

### 16.2 可选报告下载

后续可增加：

```text
GET /task-review/jobs/{jobId}/report.md
```

用于：

- 复制到 Issue。
- 保存为开发记录。
- 发送给工具维护者。

### 16.3 兼容字段

继续保留：

- `quality.dimensions`
- `quality.findings`
- `quality.suggestions`
- `quality.error`
- `report`

---

## 17. 文件级改动清单

### 17.1 Python 新增

| 文件 | 作用 |
| --- | --- |
| `evals/agent/src/cute_evals/task_review/diagnostics.py` | 诊断总编排 |
| `evals/agent/src/cute_evals/task_review/failure_rules.py` | 确定性失败规则 |
| `evals/agent/src/cute_evals/task_review/argument_checks.py` | 工具参数检查 |
| `evals/agent/src/cute_evals/task_review/recovery.py` | 恢复识别 |
| `evals/agent/src/cute_evals/task_review/narrative.py` | 综合报告生成与 Markdown 渲染 |
| `evals/agent/src/cute_evals/task_review/schemas.py` | 新增诊断和报告结构 |
| `evals/agent/schemas/task-review-diagnostics.schema.json` | 诊断 JSON Schema |
| `evals/agent/schemas/task-review-narrative.schema.json` | 叙事报告 JSON Schema |

### 17.2 Python 修改

| 文件 | 改动 |
| --- | --- |
| `evals/agent/src/cute_evals/process_view.py` | 增加稳定事件序列和工具引用 |
| `evals/agent/src/cute_evals/task_review/facts.py` | 接入确定性诊断 |
| `evals/agent/src/cute_evals/task_review/judge.py` | Judge v2、归因输出、长报告输入 |
| `evals/agent/src/cute_evals/task_review/jobs.py` | 两阶段执行、存储和恢复 |
| `evals/agent/src/cute_evals/task_review/models.py` | 新配置项 |
| `evals/agent/src/cute_evals/task_review/storage.py` | 新目录和文件读取 |
| `evals/agent/src/cute_evals/semantic_judge/evidence.py` | Evidence Packet 扩展 |
| `evals/agent/src/cute_evals/semantic_judge/contract.py` | 新证据类型 |
| `evals/agent/src/cute_evals/semantic_judge/rubrics.py` | `task-review.v2` |
| `evals/agent/src/cute_evals/semantic_judge/runner.py` | 新 Schema 校验 |
| `evals/agent/src/cute_evals/service/app.py` | 返回 diagnostics 和 narrative |

### 17.3 Java 修改

| 文件 | 改动 |
| --- | --- |
| `Cute-agent-case/src/main/java/org/wwz/ai/application/agent/review/TaskReviewTraceExportService.java` | 补充工具契约、运行事实和产物预览 |
| `Cute-agent-case/src/main/java/org/wwz/ai/application/agent/review/TaskReviewTraceSanitizer.java` | 新增字段脱敏 |
| `Cute-agent-trigger/src/main/java/org/wwz/ai/trigger/http/agent/AgentTaskReviewController.java` | 保持 API 兼容 |

### 17.4 前端修改

| 文件 | 改动 |
| --- | --- |
| `ui/src/services/taskReview.ts` | 新增诊断、归因、报告类型 |
| `ui/src/components/TaskReview/TaskReviewReport.tsx` | 增加综合报告和归因区块 |
| `ui/src/components/TaskReview/FailureAttributionPanel.tsx` | 新增错误归因面板 |
| `ui/src/components/TaskReview/NarrativeReportPanel.tsx` | 新增综合报告面板 |
| `ui/src/components/TaskReview/index.ts` | 组件导出 |

---

## 18. 实施阶段

### Phase 0：契约与 Fixture

目标：

- 固定失败归因枚举。
- 固定诊断 JSON 结构。
- 固定报告 JSON 结构。
- 创建浏览器任务、文件任务、多轮任务 Fixture。

交付：

- `task-review-diagnostics.schema.json`
- `task-review-narrative.schema.json`
- 至少 8 类失败 Fixture。

验收：

- Schema 能校验全部 Fixture。
- 旧 `task-review.v1` 继续通过。

### Phase 1：确定性诊断引擎

目标：

- 工具参数检查。
- 失败状态归类。
- 用户停止识别。
- Trace 缺口识别。
- 恢复识别。
- 重复调用识别。

交付：

- `diagnostics.py`
- `failure_rules.py`
- `argument_checks.py`
- `recovery.py`

验收：

- 每个失败调用至少生成事实记录。
- 证据不足时输出 `UNKNOWN` 或 `TRACE_GAP`。
- 不依赖 LLM 也能给出确定性失败账本。

### Phase 2：Evidence Packet 扩展

目标：

- 增加 Event Sequence。
- 增加 Tool Contract。
- 增加运行事实。
- 增加产物清单。
- 改造证据预算。

验收：

- 失败工具的错误和入参不会被普通成功日志挤掉。
- 当前 Request 隔离不回归。
- 脱敏测试通过。

### Phase 3：Judge v2

目标：

- 新增维度。
- 支持 `applicable`。
- 输出 `failureAttributions`。
- 输出 `diagnosis_quality`。
- 支持归因证据校验。

验收：

- 每条归因都有证据。
- Judge 不覆盖确定性事实。
- 证据缺失时变为 `unverified`，不是伪造归因。

### Phase 4：综合报告

目标：

- Pass 2 生成结构化叙事。
- 代码模板生成 Markdown。
- 失败不影响评分结果。

验收：

- 报告覆盖目标、过程、失败、恢复、结果、验证和建议。
- 所有重要章节有证据引用。
- 叙事不重新评分。

### Phase 5：前端

目标：

- 综合报告面板。
- 错误归因卡片。
- 责任分布。
- 参数问题列表。
- 证据跳转。

验收：

- 苹果风设计一致。
- 移动端可读。
- 旧任务兼容。

### Phase 6：校准与硬化

目标：

- 人工标注样本。
- 对比 Judge 归因。
- 调整规则。
- 检查误判率。

验收：

- 归因稳定。
- 报告不出现明显无证据结论。

---

## 19. 测试计划

### 19.1 单元测试

#### 失败归因

- 模型选错工具。
- 模型参数 JSON 非法。
- 模型参数类型错误。
- 模型选择器不存在。
- Harness Schema 不一致。
- Harness 执行器异常。
- 网络失败。
- 登录失效。
- 用户停止。
- Trace 结果缺失。
- 证据不足。

#### 参数检查

- 合法 JSON。
- 非法 JSON。
- 必填缺失。
- 类型错误。
- URL 非法。
- 路径不存在。
- 相同参数重复提交。

#### 恢复检测

- 相同参数重复失败。
- 修改参数后成功。
- 切换工具后成功。
- 用户介入后恢复。
- 未恢复。

#### 报告

- 所有章节存在。
- 没有证据的段落被拒绝。
- 报告不修改分数。
- Markdown 渲染稳定。

### 19.2 集成测试

- Java 导出完整工具事实。
- Python 快速体检生成诊断。
- Judge 生成归因。
- Narrative 生成报告。
- 前端返回兼容字段。

### 19.3 回归测试

必须覆盖已经修复过的问题：

- 当前 Request 只包含本轮消息。
- 不读取上一轮 finalSummaryText。
- 有正文的助手步骤不显示失败。
- `REQUESTED` 不算工具失败。
- 最终回答可以来自带工具调用的助手消息。
- 不重复显示最终回复卡片。

### 19.4 对抗测试

- 工具结果包含 Prompt Injection。
- 报告模型引用不存在的 toolCallId。
- 报告模型把 UNKNOWN 硬说成 Harness 问题。
- 报告模型把用户停止说成工具失败。
- 报告模型在证据缺失时给出确定性结论。

期望：

- 证据校验失败。
- 进入修复或降级为 `unverified`。
- 不污染确定性事实。

### 19.5 人工校准

准备至少：

- 20 个模型决策错误样本。
- 20 个模型参数错误样本。
- 10 个 Harness 合约样本。
- 10 个 Harness 运行样本。
- 10 个环境失败样本。
- 10 个用户停止样本。
- 10 个 Trace 缺口样本。

由开发者先做人工标签，再与 Judge 比较：

- 主责任层准确率。
- 贡献层召回率。
- `UNKNOWN` 比例。
- 误归因比例。

---

## 20. 验收标准

### 20.1 功能验收

- 每次失败工具调用都有诊断记录。
- 每条记录有主责任层。
- 每条记录有原因和证据。
- 能统计各责任层次数。
- 能识别模型参数错误和 Harness 问题。
- 能识别 Agent 自身诊断是否正确。
- 能生成综合长报告。

### 20.2 正确性验收

- 不把用户停止归因为 Agent 失败。
- 不把 Trace 缺失归因为模型能力。
- 不把无证据推测写成确定结论。
- 不覆盖确定性工具事实。
- 所有关键结论可追溯到工具调用或消息。

### 20.3 产品验收

- 开发者能在 1 分钟内看懂本次 Agent 做了什么。
- 开发者能定位第一关键失败。
- 开发者能知道应该改模型还是改 Harness。
- 开发者能看到替代执行路径。
- 报告不是时间线复制，而是解释性总结。

### 20.4 性能验收

- 快速体检仍保持 1-3 秒级。
- 结构化诊断不显著增加快速阶段耗时。
- Judge 深度复盘保持异步。
- Narrative 失败不影响快速体检和已完成的 Judge 评分。

### 20.5 安全验收

- 无密钥泄漏。
- 无 Cookie / Authorization 泄漏。
- 不读取隐藏推理。
- 不跨 Request 混入历史内容。
- 不跨用户导出信息。

---

## 21. 风险与缓解

### 风险一：Judge 归因过度自信

缓解：

- 主责任层必须有证据。
- 置信度低于阈值时输出 `UNKNOWN`。
- 确定性事实不可被覆盖。

### 风险二：Harness 和模型责任混淆

缓解：

- 区分主责任层和贡献层。
- 工具 Schema 和错误码进入证据包。
- 允许一个失败存在多个贡献因素。

### 风险三：报告太长

缓解：

- 默认展示摘要和关键章节。
- 其他章节折叠。
- Markdown 上限控制。
- 报告只使用已验证事实，不重复完整 Trace。

### 风险四：报告模型篡改结论

缓解：

- 报告模型不重新评分。
- 传入已验证 facts。
- 代码模板渲染 Markdown。
- 引用不存在证据时拒绝输出。

### 风险五：成本增加

缓解：

- 规则优先。
- Judge 只处理歧义。
- 报告使用压缩后的事实摘要。
- 使用缓存。
- 第一阶段可用同一模型，后续再拆专用模型。

### 风险六：旧任务不兼容

缓解：

- 新字段全部可选。
- schemaVersion 升级。
- 前端隐藏缺失区块。
- 保留 v1 Rubric。

---

## 22. 不在本阶段范围内

- 自动修改 Agent Prompt。
- 自动提交 Harness 修复。
- 自动重新运行原任务。
- 训练归因分类模型。
- 对隐藏 chain-of-thought 做评价。
- 把长报告作为官方 benchmark 结果。
- 把 Judge 建议直接写入代码。

---

## 23. 推荐的最终报告效果示例

以“操作浏览器发评论和关注”任务为例，最终报告可以写成：

> Agent 首先打开 X 搜索页并验证公开回复链路，成功发出 1 条评论，随后验证关注按钮可用。
>
> 第二阶段开始抓取候选帖，第一次脚本因转义错误返回空结果，Agent 修改正则后拿到 133 条候选。
>
> 第三阶段试跑 2 条评论和关注，其中输入框填充返回异常，Agent 随后改为每步独立调用并增加发送前校验。
>
> 最终只确认 1 条评论和 1 个关注生效。10 条批量任务还没有执行完成时，用户停止了本轮对话。
>
> 本次主要失败不是浏览器工具不可用，而是模型对富文本输入框的写入方式选择不稳定，属于 `MODEL_ARGUMENT`；工具只返回底层异常、没有给出可操作的选择器提示，因此同时存在 `HARNESS_CONTRACT` 贡献因素。
>
> Agent 后来能够识别输入不稳定并改变策略，`diagnosis_quality` 为中等；但它中途仅根据弹窗关闭就判断回复成功，`result_verification` 偏弱。
>
> 更稳妥的路径是：先用一条真实评论做端到端核对，再验证关注，再扩展到 2 条，最后执行批量任务。每一步都应以页面上的实际回复内容和关注状态作为完成证据。
>
> 建议优先修改 browser 工具的错误返回和输入方式，其次调整模型 Prompt，要求“发布后必须回到页面核对实际内容”，而不是只检查弹窗或脚本返回值。

这份报告需要同时显示证据引用，例如：

- `tool:call_00_xxx`
- `message:148437`
- `message:148501`

开发者可以从报告直接跳回时间线，查看原始工具入参和结果。

---

## 24. 最终交付状态

完成后，任务复盘应形成三层产品能力：

### 快速事实

回答：

- 做了什么。
- 用了多少工具。
- 哪里失败了。
- 是否恢复。

### 结构化诊断

回答：

- 为什么失败。
- 责任在模型、参数、Harness、环境还是用户。
- Agent 自己有没有诊断对。

### 综合报告

回答：

- 整条执行轨迹发生了什么。
- 最终结果是否可信。
- 有没有更好的路径。
- 开发者下一步应该改什么。

这三层共同构成一个面向 Agent 开发者的完整复盘闭环，而不是只有一个模糊的 Agent 总分。
