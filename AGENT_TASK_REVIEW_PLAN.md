# Agent 单次任务复盘开发计划

## 1. 文档信息

- 文档状态：已实施（MVP）
- 适用范围：Cute Agent 前端、Java 后端、Python 评测侧车
- 目标版本：单次任务复盘 MVP
- 不包含：Codex 对照、批量 Benchmark、自动 Badcase 回流、每日定时评测

## 2. 背景与目标

用户刚完成一轮 Agent 对话后，通常最想知道四件事：

1. 这次任务到底完成了没有。
2. 执行路径是否绕远，工具选择是否合理。
3. 哪些工具失败过，失败后是否恢复。
4. 最终结果是否经过验证，是否存在“看起来完成，其实没完成”。

当前已有能力分散在 Python 评测侧车中：

- `ledger-export`：从 Java Execution Ledger 导出脱敏 Trace。
- `process-view`：把 Trace 投影成逐步骤视图。
- `ops sample`：确定性的线上 Trace 健康审计。
- `trajectory-quality`：可选的 LLM 轨迹质量裁判。
- semantic judge：带证据引用的语义评分和缓存。

本次要做的不是重新造一套评测系统，而是把这些能力串成一个用户可直接使用的产品流程：

```text
刚完成一轮对话
  -> 点击“复盘这次任务”
  -> 确认本次 Run
  -> 快速体检
  -> LLM 深度复盘
  -> 查看证据和改进建议
```

### 2.1 产品定义

产品名称建议使用“任务复盘”或“轨迹诊断”，不要使用“最优性评分”。

原因：

- 单次轨迹无法证明全局最优。
- 没有明确验收标准时，只能判断执行事实和明显瑕疵。
- LLM 可以判断“路径是否合理”，但不能把主观判断包装成绝对结论。

因此本功能回答的是：

> 这次任务完成得怎么样，执行路径有没有明显问题，哪些地方可以做得更好？

而不是：

> 这次轨迹是不是理论上最优的？

### 2.2 成功标准

MVP 完成后，用户应当可以：

1. 在刚结束的对话轮次上直接看到“复盘这次任务”。
2. 不需要复制 Trace ID，不需要打开命令行。
3. 在 3 秒左右先看到确定性体检结果。
4. 在几十秒内看到带证据的 AI 深度复盘。
5. 每个问题都能回到具体步骤、工具调用和结果。
6. Judge 不可用时明确显示“未验证”，而不是伪造结论。

## 3. 核心设计原则

### 3.1 事实和判断分离

确定性事实只来自 Execution Ledger、工具调用记录和产物元数据：

- 执行状态
- 耗时
- Token
- 工具调用次数
- 工具失败次数
- 完全相同的重复调用
- 失败后的重试
- Artifact 是否存在
- 是否超出预算
- 是否被用户停止

LLM 只负责语义判断：

- 工具选择是否合理
- 是否有更简单的路径
- 是否进行了无意义的语义重复搜索
- 是否在证据不足时下结论
- 最终回答是否清楚、诚实
- 是否存在“看起来完成，其实没完成”

LLM 不得推翻确定性的失败事实。

### 3.2 不承诺“绝对最优”

结果页不显示一个虚假的 0～100 总分。

使用分维度等级：

```text
完成度：高 / 中 / 低 / 无法判断
工具选择：合理 / 一般 / 不合理 / 无法判断
错误恢复：良好 / 一般 / 较弱 / 无法判断
执行效率：正常 / 有冗余 / 明显绕路 / 无法判断
结果可信度：高 / 中 / 低 / 无法判断
```

后续只有在积累参考轨迹后，才增加“和参考轨迹相比更优/更差”的相对结论。

### 3.3 快速结果优先

评测分成两层：

- 快速体检：确定性规则，目标 1～3 秒。
- 深度复盘：LLM Judge，目标几十秒，允许异步完成。

即使深度复盘失败，快速体检也必须可用。

### 3.4 只读和脱敏

- Python 不连接 Java 数据库。
- Python 不直接读取 Ledger 表。
- Trace 通过 Java 只读导出端口生成。
- 导出前完成脱敏和残留敏感信息扫描。
- 原始 Trace 不进入 Git。
- 报告默认保存在本机评测目录。

### 3.5 以“用户轮次”为单位

一次用户提问可能对应多个 Run，例如用户回答 Agent 的问题后会生成 `resume_` 续跑。

默认复盘整个用户轮次，而不是只复盘最后一个 Run：

```text
用户提问
  -> 初始 Run
  -> 用户补充信息
  -> resume_ 续跑
```

前端使用现有 `turnGroups` 能力收集同一轮的 `requestId` 列表，再提交给评测后端。

MVP 限制：

- 最多 20 个 requestId。
- 只允许同一会话、同一用户的 Run。
- 只允许终态 Run。
- 子 Agent 默认包含在汇总中，不单独提供入口。

## 4. 完整使用流程

### 4.1 入口出现

在对话时间线的助手消息工具栏增加：

> 复盘这次任务

显示条件：

- 当前轮次已经结束。
- 当前轮次至少有一个可导出的终态 Run。
- 当前用户有权访问该会话。
- 当前页面不是子 Agent 的只读子会话，除非后续单独开放。

运行中、等待用户输入、正在重连时：

- 按钮隐藏，或显示禁用状态。
- 禁用原因使用 tooltip 说明：“任务结束后可以复盘”。

同一个用户轮次只显示一个入口，不按每个 Run 重复显示。

### 4.2 打开复盘面板

点击后打开 `TaskReviewStartDialog`。

面板顶部显示：

- 本次用户请求摘要
- 会话标题
- 本轮包含的 Run 数
- 是否包含续跑
- 是否包含子 Agent

Run 列表默认选中整个用户轮次，展开后可以查看每个 Run：

- 执行时间
- 模型
- 状态
- 耗时
- Token
- 工具调用次数
- 子 Agent 数
- Artifact 数量

面板底部提供：

- `仅做快速体检`
- `快速体检 + AI 深度复盘`
- `开始复盘`

默认选择“快速体检 + AI 深度复盘”；如果 Judge 未配置，则自动降级为“仅做快速体检”。

### 4.3 点击开始复盘

前端执行：

1. 调用 Java preflight 接口获取权威摘要。
2. 校验用户是否仍有权限。
3. 调用 Python Task Review Job API。
4. 创建异步任务。
5. 跳转到复盘详情页，或打开右侧详情面板。
6. 轮询任务状态。

页面进度显示为分阶段，而不是单一转圈：

```text
正在导出执行轨迹
正在重建步骤
正在检查工具失败和重复调用
正在检查产物和预算
正在分析任务完成度
正在生成复盘报告
```

建议状态机：

```text
created
  -> exporting
  -> projecting
  -> deterministic_audit
  -> fast_ready
  -> deep_running
  -> completed
  -> partial
  -> failed
  -> cancelled
```

`fast_ready` 之后，前端立即展示第一屏。

### 4.4 第一屏：确定性体检

第一屏只展示事实，不展示主观总分。

#### 第一块：执行状态

明确区分“执行成功”和“任务完成”：

```text
执行状态：成功
任务完成度：AI 推断为高，尚未提供明确验收标准
```

如果 Run 失败：

```text
执行状态：失败
任务完成度：未完成
```

允许的用户结论：

- 完成
- 部分完成
- 未完成
- 无法判断

内部字段：

```json
{
  "executionStatus": "SUCCESS",
  "completion": {
    "conclusion": "completed",
    "basis": "inferred",
    "advisory": true,
    "summary": "执行成功，产物存在，但未执行明确的最终验证"
  }
}
```

`basis` 可选值：

- `oracle`：来自确定性 Oracle
- `explicit`：用户或 Case 明确提供的验收标准
- `inferred`：LLM 根据原始请求推断
- `none`：没有足够信息

硬规则：

- Run 为 `FAILED`、`TIMEOUT`、`STOPPED` 时，不能显示“完成”。
- Oracle 失败时，LLM 不能把结论升级为完成。
- 没有验收标准时，LLM 的完成度结论必须标记为 advisory。

#### 第二块：关键指标

- 总耗时
- 首 Token 延迟
- 总 Token
- Prompt Token
- 缓存 Token
- 工具调用次数
- 工具失败次数
- 完全相同的重复调用次数
- 失败后重试次数
- 子 Agent 数
- Artifact 数
- 是否超出预算
- 是否被停止

#### 第三块：问题列表

每条问题包含：

```json
{
  "code": "tool_failure",
  "severity": "warning",
  "title": "shell 工具调用失败",
  "detail": "第 6 次工具调用返回 FAILED，之后使用相同参数重试了 3 次。",
  "evidenceRefs": ["tool:6", "tool:7", "tool:8", "tool:9"]
}
```

点击问题后打开证据抽屉，展示：

- 工具名
- 输入参数摘要
- 返回结果摘要
- 状态
- 耗时
- 后续是否恢复

### 4.5 第二层：AI 深度复盘

深度复盘只增加语义判断，不改变第一屏的事实。

输出分维度：

```text
完成度：高
工具选择：中
错误恢复：低
执行效率：偏低
结果可信度：中
```

每个维度必须有：

- 结论
- 解释
- 证据引用
- 置信度
- 是否为推断

结论必须明确标记：

> AI 复盘建议，不是官方评分

### 4.6 结果页结构

结果页按以下顺序展示：

1. 一句话结论
2. 完成情况
3. 轨迹体检
4. 质量问题
5. 改进建议

#### 一句话结论

示例：

> 任务完成了，但路径不够简洁，主要问题是重复搜索和失败重试。

#### 完成情况

- 用户原始目标
- 系统推断或用户提供的验收标准
- 已满足的要求
- 未满足的要求
- 未验证的要求
- 最终交付物

#### 轨迹体检

- 逐步时间线
- 成功/失败标记
- Token 和耗时
- 重复调用
- 异常步骤
- 子 Agent 汇总
- Artifact 列表

#### 质量问题

- 工具选择
- 错误恢复
- 停止时机
- 结果验证
- 回答质量
- 是否存在“看起来完成，其实没完成”

#### 改进建议

每条建议必须可执行，例如：

- 搜索失败后先检查错误类型，不要重复相同调用。
- 删除文件前先确认路径和远端工作区行为。
- 生成报告后至少读取一次文件，确认内容完整。
- 任务完成后及时停止。

### 4.7 结果后续操作

MVP 提供：

- 重新分析
- 复制报告链接
- 查看脱敏 Trace
- 返回对话

后续版本提供：

- 补充或修改验收标准，只重跑深度复盘
- 保存为 Badcase 草稿
- 加入参考轨迹库
- 与另一个 Run 或另一个 Agent 对照

## 5. 总体技术架构

### 5.1 推荐架构

```text
React UI
  -> Java preflight API
  -> Python Task Review API
       -> Java trace export API
       -> process view
       -> deterministic audit
       -> optional LLM judge
       -> report store
  -> UI polling
```

前端不直接访问 Java 数据库，也不直接访问 Ledger 表。

Java 仍然是会话归属、Trace 导出和脱敏的唯一入口。

Python 只负责评测编排、确定性分析、LLM Judge 和报告存储。

### 5.2 关键架构决策

#### 决策一：不直接复用测试 CLI 的热路径

当前 `EvalTraceExportCli` 会启动一个独立的 Spring 上下文，适合离线运维，不适合交互式接口。

本功能必须在已经运行的 Java 应用内提供只读导出服务，避免：

- 再启动一个 Spring 进程。
- Python 持有数据库凭据。
- 导出延迟不可控。
- 测试代码被生产接口依赖。

实施时抽取生产可用的 Trace 导出和脱敏服务，现有 CLI 改为调用同一服务。

#### 决策二：前端到 Python 的请求走同源网关

建议部署形态：

```text
浏览器
  -> /api/agent/task-review/*       Java
  -> /eval-api/task-review/*        Python
```

通过同源反向代理避免 CORS，并让浏览器继续使用现有登录态。

Python 调 Java 导出接口时转发原始认证信息，不信任请求体中的 `userId`。

如果后续部署为多租户或跨机器，升级为短时一次性 Trace Ticket，Python 不保存用户长期 Cookie 或 Authorization。

#### 决策三：先用轮询，不先做 SSE

MVP 使用 1 秒轮询：

```text
GET /eval-api/task-review/jobs/{jobId}
```

理由：

- 任务通常只有几个阶段。
- 前端已有请求封装。
- 断线重连和页面刷新更容易处理。

后续如果单个任务超过 2 分钟，再增加 SSE。

## 6. API 设计

### 6.1 Java Preflight API

用途：打开复盘面板时获取权威摘要，避免完全依赖前端缓存。

```http
POST /api/agent/task-review/preflight
Content-Type: application/json
```

请求：

```json
{
  "sessionId": "session-001",
  "requestIds": ["req-001", "resume_req-001"]
}
```

响应：

```json
{
  "code": "0000",
  "info": "success",
  "data": {
    "sessionId": "session-001",
    "requestIds": ["req-001", "resume_req-001"],
    "title": "整理项目并生成报告",
    "canReview": true,
    "blockers": [],
    "runs": [
      {
        "requestId": "req-001",
        "status": "SUCCESS",
        "model": "default",
        "createdAt": "2026-11-15T10:00:00",
        "durationMs": 42500,
        "totalTokens": 18000,
        "toolCount": 9,
        "subagentCount": 0,
        "artifactCount": 1
      }
    ],
    "aggregate": {
      "durationMs": 42500,
      "totalTokens": 18000,
      "toolCount": 9,
      "failedToolCount": 1,
      "artifactCount": 1
    }
  }
}
```

校验：

- 当前用户有会话权限。
- 所有 requestId 属于同一 session。
- 最多 20 个 requestId。
- 至少一个终态 Run。
- 运行中或等待输入的 Run 不允许复盘。

### 6.2 Java Trace Export API

用途：供 Python 拉取脱敏后的 Trace Bundle。

```http
POST /api/agent/task-review/traces
Content-Type: application/json
```

请求：

```json
{
  "sessionId": "session-001",
  "requestIds": ["req-001", "resume_req-001"]
}
```

响应：

```json
{
  "schemaVersion": "1.0",
  "sessionId": "session-001",
  "requestIds": ["req-001", "resume_req-001"],
  "runs": [
    {
      "requestId": "req-001",
      "trace": {}
    }
  ],
  "artifacts": [
    {
      "artifactId": "artifact-001",
      "type": "file",
      "name": "report.md",
      "size": 12000,
      "sha256": "..."
    }
  ]
}
```

约束：

- 响应只包含脱敏 Trace。
- 不复制原始 Artifact 内容。
- 默认最大 20 MB；超过上限返回可解释错误。
- 单轮最多 20 个 Run。
- 必须校验所有 Run 的归属。
- 不得记录认证头、Cookie 或完整 Trace 到普通应用日志。

### 6.3 Python 创建复盘任务

```http
POST /eval-api/task-review/jobs
Content-Type: application/json
```

请求：

```json
{
  "sessionId": "session-001",
  "requestIds": ["req-001", "resume_req-001"],
  "mode": "standard",
  "includeLlmJudge": true,
  "criteria": null,
  "clientRequestId": "ui-uuid-001"
}
```

字段：

- `mode`：`fast` 或 `standard`
- `includeLlmJudge`：是否请求深度复盘
- `criteria`：可选的用户验收标准
- `clientRequestId`：前端幂等键

响应：

```json
{
  "jobId": "review-20261115-001",
  "status": "fast_ready",
  "fastReady": true,
  "deepStatus": "running",
  "report": {}
}
```

说明：

- Python 创建任务时同步完成 Java Trace 拉取和基础校验。
- 目标是在 3 秒左右返回 fast report。
- 如果导出或快速体检超过阈值，可以先返回 `status=projecting`，由前端继续轮询。
- 认证信息只在本次请求内存中使用，不写入任务文件。

### 6.4 Python 查询任务

```http
GET /eval-api/task-review/jobs/{jobId}
```

响应：

```json
{
  "jobId": "review-20261115-001",
  "status": "completed",
  "progress": [
    {"stage": "exporting", "status": "completed"},
    {"stage": "projecting", "status": "completed"},
    {"stage": "deterministic_audit", "status": "completed"},
    {"stage": "deep_review", "status": "completed"}
  ],
  "report": {},
  "limitations": []
}
```

### 6.5 Python 列表和取消

```http
GET /eval-api/task-review/jobs?limit=20
POST /eval-api/task-review/jobs/{jobId}/cancel
```

列表只返回摘要，不返回完整 Trace。

### 6.6 错误格式

Python 统一返回：

```json
{
  "error": {
    "code": "TRACE_EXPORT_FAILED",
    "message": "无法导出该轮执行轨迹",
    "retryable": true
  }
}
```

建议错误码：

- `UNAUTHORIZED`
- `RUN_NOT_FOUND`
- `RUN_NOT_TERMINAL`
- `RUN_OWNERSHIP_DENIED`
- `TRACE_EXPORT_FAILED`
- `TRACE_TOO_LARGE`
- `TRACE_INVALID`
- `JUDGE_NOT_CONFIGURED`
- `JUDGE_TIMEOUT`
- `JOB_NOT_FOUND`
- `JOB_CANCELLED`

## 7. 任务状态和存储

### 7.1 任务状态

```text
created
exporting
projecting
deterministic_audit
fast_ready
deep_running
completed
partial
failed
cancelled
```

状态规则：

- `fast_ready` 表示第一屏已经可以展示。
- `partial` 表示快速体检成功，但深度复盘失败或未验证。
- `completed` 表示深度复盘也已结束；不表示任务一定成功。
- `failed` 表示没有可展示的快速体检结果。

### 7.2 本地目录

默认：

```text
%USERPROFILE%\.cute-evals\task-reviews\<jobId>\
```

可通过 `CUTE_EVAL_REVIEW_HOME` 覆盖。

每个任务目录：

```text
job.json
request.json
trace/
  export.json
process-view.json
deterministic.json
advisory.json
report.json
progress.json
```

要求：

- 所有文件使用临时文件加原子替换写入。
- `request.json` 不保存认证头。
- Trace 和报告不提交到 Git。
- 默认保留 30 天，启动时清理过期任务。
- 崩溃恢复时，优先复用已保存的 Trace 和确定性报告。

### 7.3 幂等和缓存

幂等键：

```text
sessionId + requestIds + traceSha256 + mode
```

规则：

- 同一 Trace 的确定性报告可复用。
- 深度复盘缓存同时包含 Rubric 版本、Prompt 版本和 Judge 模型。
- 命中缓存时直接返回已有报告，并显示“使用缓存结果”。
- “重新分析”可以跳过缓存，但不能重复导出 Trace。

## 8. 确定性分析层

### 8.1 复用现有能力

优先复用：

- `process_view.py`：生成稳定的逐步骤视图。
- `ops_sample.audit_trace`：健康审计和风险发现。
- `trajectory_quality.deterministic_metrics`：Token、重复调用、失败恢复等指标。
- `RunTrace`：统一 Trace 模型。

建议把通用确定性分析抽到独立模块，避免 `ops sample` 和任务复盘各自维护一套规则：

```text
task_review/facts.py
  -> build_fast_report(trace, process_view)
```

`ops_sample` 继续调用同一函数，输出格式可以保持兼容。

### 8.2 确定性发现

MVP 支持以下发现：

| code | 含义 | 是否确定 |
| --- | --- | --- |
| `run_failed` | Run 失败 | 是 |
| `run_timeout` | Run 超时 | 是 |
| `run_stopped` | 用户停止 | 是 |
| `budget_exceeded` | 超出预算 | 是 |
| `empty_final_answer` | 成功但最终回答为空 | 是 |
| `tool_failure` | 工具返回失败 | 是 |
| `repeated_identical_call` | 工具名和参数完全相同 | 是 |
| `retry_after_failure` | 失败后用相同工具和参数重试 | 是 |
| `no_recovery_after_failure` | 失败后没有后续成功调用 | 是 |
| `artifact_missing` | 预期产物不存在 | 是，前提是有明确预期 |
| `artifact_unverified` | 产物存在但没有验证记录 | 是，前提是有明确验证规则 |
| `slow_run` | 超过配置的时间阈值 | 是 |
| `high_token_usage` | 超过配置的 Token 阈值 | 是 |

### 8.3 不应伪装成确定性的事实

以下判断只能由 LLM 给出，不能在第一屏写成硬事实：

- 某个文件“不相关”
- 某个搜索“没有必要”
- 某个工具“选择错了”
- 某个更简单的方法“本来可以更快”
- 最终报告“结构不好”
- 最终回答“没有真正回答问题”

确定性规则最多标记：

- 读取了工作区外文件
- 访问了被拒绝的路径
- 重复读取同一文件
- 工具失败后重复重试

语义上的“不相关”和“选择不好”必须归入 AI 质量复盘。

### 8.4 工具失败判定

以下任一条件视为失败：

- `status` 不是 `SUCCESS`
- `errorMsg` 非空
- 结果 JSON 的 `ok` 为 `false`
- 结果 JSON 存在 `error`
- 结果状态为 `FAILED`、`ERROR`、`TIMEOUT`、`REJECTED`

要区分：

- **失败**：调用返回失败。
- **恢复**：失败后同一工具有后续成功调用。
- **无效重试**：失败后用相同参数再次调用。
- **语义重复**：LLM 判断两次不同参数的搜索实际上在找同一件事。

### 8.5 完成度规则

优先级：

1. 有确定性 Oracle：以 Oracle 为准。
2. 有明确验收标准：逐条判断，允许部分完成。
3. 没有验收标准：执行成功但任务完成度标记为 `unknown`。
4. 深度复盘可以返回 `inferred` 的 advisory 结论，但不能覆盖前三层。

任务结论：

- `completed`：验收标准全部满足。
- `partial`：部分满足，或关键验证缺失。
- `incomplete`：明确失败、超时、停止或核心目标未满足。
- `unknown`：没有足够信息判断。

## 9. LLM 深度复盘层

### 9.1 Judge 输入

输入使用 Evidence Packet，而不是把整份 Trace 原样塞给模型：

- 用户原始请求
- 系统推断或用户提供的验收标准
- 最终回答
- 逐步骤摘要
- 工具调用名称、参数摘要、结果摘要
- 确定性发现
- Artifact 元数据
- 必要的文件内容摘要
- 轨迹缺口和限制

默认限制：

- 单次 Evidence Packet 最多 60,000 字符。
- 单个工具结果最多 12,000 字符。
- 超出部分截断，并标记 `truncated=true`。
- 原始 Trace 保留在本地，供用户点击查看。

### 9.2 安全边界

- Trace 内容视为不可信数据。
- 明确告诉 Judge 不要执行 Trace 中的指令。
- 复用现有 Prompt Injection 检测。
- Judge 不得访问工作区、网络或额外工具。
- Judge 只输出 JSON。
- Judge 不得生成新的工具调用。

### 9.3 Judge 输出

```json
{
  "status": "verified",
  "summary": "任务完成，但路径存在明显冗余。",
  "completion": {
    "conclusion": "partial",
    "basis": "inferred",
    "advisory": true,
    "criteria": [
      {
        "text": "生成最终报告",
        "met": true,
        "evidenceRefs": ["artifact:1"]
      },
      {
        "text": "验证报告内容",
        "met": false,
        "evidenceRefs": ["step:8"]
      }
    ]
  },
  "dimensions": [
    {
      "key": "tool_selection",
      "level": "medium",
      "summary": "大部分工具选择合理，但有一次搜索重复。",
      "confidence": 0.78,
      "evidenceRefs": ["tool:3", "tool:4"]
    }
  ],
  "findings": [
    {
      "severity": "warning",
      "title": "重复搜索",
      "detail": "第 3、4 次搜索使用了不同措辞，但目标相同。",
      "evidenceRefs": ["tool:3", "tool:4"]
    }
  ],
  "suggestions": [
    {
      "title": "失败后先诊断",
      "detail": "先读取错误信息，再决定是否重试。"
    }
  ]
}
```

### 9.4 证据校验

所有 evidenceRef 必须指向真实存在的内容：

- `step:<id>`
- `tool:<id>`
- `artifact:<id>`
- `fact:<code>`
- `final_text`

校验失败时：

- 不伪造证据。
- 该条发现标记为 `unverified` 或被丢弃。
- 整个 Judge 结果的 `status` 记录为 `partial`。
- 报告中明确显示“部分结论缺少可验证证据”。

### 9.5 未验证状态

以下情况统一记为 `unverified`：

- Judge 未配置
- Judge 超时
- 返回非法 JSON
- 证据引用不存在
- 证据引用与原文不匹配
- 请求被取消

`unverified` 不是 0 分，也不是失败，只是“没有足够证据判断”。

### 9.6 成本和延迟控制

MVP 默认：

- 每个任务一次深度复盘。
- 使用服务端配置的 Judge 模型和参数。
- 前端不选择模型、不传 API Key。
- 超时 180 秒。
- 最多 2 个并发深度任务。
- 相同 Trace 和 Rubric 命中缓存。
- 支持“只重跑深度复盘”，不重新导出 Trace。

如果现有 `trajectory-quality` 的单步裁判导致延迟过高，再增加批量 Judge 模式；MVP 不先改裁判语义。

## 10. 前端设计

### 10.1 页面和信息架构

建议增加两个路由：

```text
/task-review              复盘记录
/task-review/:jobId       复盘详情
```

入口：

- 对话助手消息工具栏中的“复盘这次任务”
- 复盘记录页

### 10.2 组件拆分

```text
ui/src/services/taskReview.ts
ui/src/hooks/useTaskReviewJob.ts
ui/src/components/TaskReview/
  TaskReviewEntry.tsx
  TaskReviewStartDialog.tsx
  TaskReviewReport.tsx
  TaskReviewSummary.tsx
  TaskReviewMetrics.tsx
  TaskReviewFindings.tsx
  TaskReviewTimeline.tsx
  TaskReviewQuality.tsx
  TaskReviewEvidenceDrawer.tsx
ui/src/pages/TaskReview/
  index.tsx
  detail.tsx
```

复用：

- 现有 `shadcn/ui` 组件
- Lucide 图标
- 现有 Motion 动画
- 现有请求封装和错误提示

不为这个功能新增全局状态库；使用局部 hook 和 URL 参数即可。

### 10.3 交互细节

- 点击开始后按钮立即禁用，避免重复创建任务。
- 页面刷新后用 `jobId` 恢复任务。
- 快速结果先渲染，深度结果使用骨架屏或渐进区块。
- 任务失败时保留已完成的快速报告。
- 用户离开页面不取消任务。
- 用户主动取消时才调用取消接口。
- 错误提示必须说明是否可重试。

### 10.4 证据抽屉

点击任意问题、维度或时间线步骤，打开右侧证据抽屉：

- 步骤编号
- 工具名
- 输入参数
- 结果摘要
- 状态
- 耗时
- 关联 Artifact
- “查看脱敏原始片段”

抽屉只展示已脱敏数据。

### 10.5 响应式和可访问性

- 桌面端：左侧摘要，右侧时间线。
- 移动端：单列堆叠。
- 进度区域使用 `aria-live="polite"`。
- 对话框支持键盘关闭和焦点回收。
- 颜色不能作为唯一状态表达，必须同时显示文字或图标。
- 长文本、长工具名和长路径必须可换行，不撑破布局。

## 11. Java 后端实施

### 11.1 新增生产导出服务

建议新增：

```text
Cute-agent-case/src/main/java/org/wwz/ai/application/agent/review/
  TaskReviewApplicationService.java
  ExecutionTraceReviewExportService.java
```

职责：

- 校验用户归属。
- 读取 Run 详情。
- 组装脱敏 Trace Bundle。
- 统计 Run 摘要。
- 限制 Run 数、Trace 大小和步骤数。
- 不写数据库。

### 11.2 Trace 组装和脱敏

当前 `EvalTraceExportCli` 和 `EvalTraceSanitizer` 位于测试代码中，不能直接作为生产接口依赖。

实施步骤：

1. 抽取可复用的 Trace 组装逻辑。
2. 抽取脱敏器到生产模块。
3. 保留并复用现有脱敏测试用例。
4. 让 `EvalTraceExportCli` 调用抽取后的服务。
5. 让新的 HTTP 导出接口调用同一服务。

脱敏要求：

- API Key
- Authorization
- Cookie
- 密码
- 手机号
- 邮箱
- 身份证
- 明显地址
- 内网地址
- 绝对路径
- 敏感下载链接
- Artifact 原始内容

导出后再次执行残留敏感模式扫描，扫描失败拒绝返回。

### 11.3 Controller

建议新增：

```text
Cute-agent-trigger/src/main/java/org/wwz/ai/trigger/http/agent/AgentTaskReviewController.java
```

接口：

- `POST /api/agent/task-review/preflight`
- `POST /api/agent/task-review/traces`

Controller 只负责：

- 接收请求
- 调用应用服务
- 转换 VO
- 返回现有 `Response<T>` 结构

不要在 Controller 中拼 Trace 或做脱敏。

### 11.4 归属和权限

必须使用：

- `UserRequestContext.requireUserId()`
- `ConversationSessionAuthorizationService`
- 带 `userId` 的 Ledger 查询重载

禁止：

- 仅凭 `sessionId` 读取。
- 信任前端传入的 `userId`。
- 把跨用户时间窗查询暴露到 HTTP。

### 11.5 测试

Java 测试至少覆盖：

- 非 owner 访问被拒绝。
- 同一 session 校验。
- 运行中的 Run 被拒绝。
- 多 Run 顺序正确。
- 续跑 Run 被包含。
- Trace 脱敏。
- 超过数量或大小限制。
- 返回结构稳定。
- 原 CLI 测试继续通过。

## 12. Python 后端实施

### 12.1 服务形态

在 `evals/agent` 增加可选 `service` 依赖：

```toml
service = [
  "fastapi>=0.115,<1",
  "uvicorn[standard]>=0.30,<1",
]
```

不改变现有 CLI 的默认安装。

新增入口：

```text
src/cute_evals/service/app.py
```

启动：

```powershell
cd evals/agent
uv sync --extra service --extra judge
uv run uvicorn cute_evals.service.app:app --host 127.0.0.1 --port 8765
```

### 12.2 模块拆分

```text
src/cute_evals/task_review/
  models.py          请求、状态、报告模型
  java_client.py     调用 Java preflight / trace export
  jobs.py            任务状态机和并发控制
  facts.py           确定性体检
  judge.py           LLM 深度复盘
  report.py          报告组装和 schema 校验
  storage.py         原子写入、缓存、清理
```

### 12.3 复用现有代码

- `RunTrace.from_dict`
- `project_process_view`
- `project_ledger_process_view`
- `ops_sample.audit_trace`
- `trajectory_quality.deterministic_metrics`
- `semantic_judge` 的 Evidence Packet 和证据校验
- `trajectory_quality.analyze_trace`

不要复制现有规则到新模块；先抽取，再复用。

### 12.4 Job 执行器

使用线程池或后台任务：

```text
MAX_FAST_WORKERS=4
MAX_DEEP_WORKERS=2
```

任务流程：

1. 校验请求。
2. 调用 Java preflight。
3. 调用 Java trace export。
4. 校验 Trace 大小和 schema。
5. 写入任务目录。
6. 生成 ProcessView。
7. 生成确定性报告。
8. 更新为 `fast_ready`。
9. 如启用 Judge，生成深度报告。
10. 组装最终报告。
11. 原子写入 `report.json`。
12. 标记 `completed` 或 `partial`。

### 12.5 重启恢复

启动时扫描任务目录：

- `completed`、`partial`、`failed`、`cancelled`：保持不变。
- `fast_ready`：如果请求了深度复盘，重新排队。
- 已有 Trace 但未完成快速体检：从 `deterministic_audit` 继续。
- 没有 Trace 的任务：标记为 `failed`，提示重试。

### 12.6 配置

新增：

```text
config/task-review.yaml
```

示例：

```yaml
server:
  host: 127.0.0.1
  port: 8765

java:
  baseUrl: http://127.0.0.1:8080
  preflightPath: /api/agent/task-review/preflight
  tracePath: /api/agent/task-review/traces
  timeoutSeconds: 15

judge:
  enabled: true
  profile: default
  timeoutSeconds: 180
  maxEvidenceChars: 60000

limits:
  maxRequestIds: 20
  maxTraceBytes: 20971520
  maxSteps: 2000
  maxArtifacts: 100

storage:
  home: ~/.cute-evals/task-reviews
  retentionDays: 30
```

敏感配置只从环境变量或现有配置中心读取，不进入前端。

### 12.7 测试

Python 测试至少覆盖：

- 创建任务成功。
- 缺少认证失败。
- Java 导出失败可重试。
- Trace 超限。
- 快速报告生成。
- 工具失败和重复调用识别。
- 续跑多 Run 聚合。
- Judge 未配置。
- Judge 超时。
- 非法 JSON。
- 伪造证据引用。
- 缓存命中。
- 并发任务。
- 服务重启恢复。
- 报告 schema 校验。

## 13. 安全与隐私

- 不在前端传递 `userId` 作为身份依据。
- 不在报告中写认证头、Cookie、API Key。
- 不把原始 Trace 写入 Git。
- 不把完整 Trace 打进普通日志。
- 证据抽屉只展示脱敏片段。
- Judge 请求只发送必要证据。
- Judge 模型和 API Key 只存在于服务端配置。
- 任务目录使用当前用户权限。
- 生产环境增加服务间认证。
- 如跨机器部署，使用一次性 Trace Ticket，不保存用户长期凭证。

## 14. 性能和资源目标

目标值按本机单用户环境估算：

| 阶段 | 目标 |
| --- | --- |
| Preflight | P95 < 300ms |
| Java Trace 导出 | P95 < 1s |
| 快速体检 | P95 < 2s |
| 首屏快速结果 | P95 < 3s |
| 深度复盘 | P50 < 45s，P95 < 120s |
| 进度刷新 | 1s |
| 单任务 Trace | 默认 < 20MB |
| 单任务步骤 | 默认 < 2000 |
| 同时深度任务 | 最多 2 个 |

超过限制时：

- 仍生成快速报告。
- 报告显示“由于轨迹过大，深度复盘未执行”。
- 提供重新分析和下载摘要入口。

## 15. 失败处理

| 场景 | 用户看到 | 系统行为 |
| --- | --- | --- |
| Java 导出失败 | 导出失败，可重试 | 不创建深度任务 |
| 用户无权限 | 无权查看该任务 | 返回 403 |
| Run 运行中 | 任务结束后再复盘 | 阻止创建 |
| Trace 超大 | 已生成简化体检 | 跳过深度 Judge |
| Judge 未配置 | AI 复盘不可用 | 只显示快速报告 |
| Judge 超时 | 深度复盘未完成 | 保留快速报告，可重试 |
| Judge 伪证据 | 部分结论未验证 | 丢弃无效结论 |
| 服务重启 | 任务已中断 | 从已保存 Trace 恢复 |
| 浏览器刷新 | 继续显示进度 | 通过 jobId 恢复 |

任何情况下都不能：

- 把未配置 Judge 显示成 0 分。
- 把失败 Run 显示成完成。
- 把基础设施错误算成 Agent 能力问题。

## 16. 开发阶段和顺序

以下为粗略单人估算，实际以代码评审和验证结果为准。

### M0：契约和 Fixture

预计 0.5～1 天。

交付：

- `task_review` JSON Schema。
- 成功、失败、重复调用、续跑、Artifact 缺失的 Trace Fixture。
- Java/Python/UI 共享的字段定义。

验证：

- Fixture 能通过 schema 校验。
- 快速报告 Golden File 稳定。

### M1：Java 只读导出

预计 1.5～2 天。

交付：

- 生产 Trace 导出服务。
- 脱敏器抽取。
- preflight API。
- trace export API。
- 权限和大小限制。

验证：

- Java 单测。
- 非 owner 访问失败。
- 脱敏扫描通过。

### M2：Python 快速体检

预计 1.5～2 天。

交付：

- FastAPI service。
- Job 状态机。
- Java client。
- ProcessView 和确定性报告。
- 本地存储、缓存、清理。

验证：

- 单题真实 Trace 快速报告。
- 失败工具和重复调用识别。
- 1～3 秒首屏目标。

### M3：LLM 深度复盘

预计 1～1.5 天。

交付：

- Judge 输入组装。
- 证据校验。
- 分维度结果。
- unverified 降级。
- 只重跑深度分析。

验证：

- 无 Judge 配置时不伪造。
- 伪证据被拒绝。
- 超时保留快速报告。

### M4：前端接入

预计 2～2.5 天。

交付：

- 工具栏入口。
- 开始复盘对话框。
- 复盘详情页。
- 进度轮询。
- 证据抽屉。
- 复盘记录页。

验证：

- 正常成功任务。
- 失败任务。
- 续跑任务。
- 页面刷新恢复。
- 移动端布局。

### M5：联调和硬化

预计 1～1.5 天。

交付：

- 端到端测试。
- 错误处理和重试。
- 文档。
- 性能检查。
- 隐私检查。

验证：

- 完整使用流程。
- 全量 Python 测试。
- 相关 Java 测试。
- 前端测试和构建。

## 17. 测试计划

### 17.1 Java

- `TaskReviewPreflightControllerTest`
- `TaskReviewTraceControllerTest`
- `ExecutionTraceReviewExportServiceTest`
- `TraceSanitizerTest`
- `EvalTraceExportCliTest`

### 17.2 Python

- `test_task_review_jobs.py`
- `test_task_review_facts.py`
- `test_task_review_judge.py`
- `test_task_review_storage.py`
- `test_task_review_api.py`
- `test_task_review_schema.py`

### 17.3 UI

- 入口显示条件测试。
- 对话框 preflight 测试。
- 轮询状态测试。
- 快速报告渲染测试。
- 深度报告渲染测试。
- 证据抽屉测试。
- 错误和重试测试。
- 移动端 snapshot 或视觉检查。

### 17.4 端到端

至少准备五条真实路径：

1. 成功完成任务，有一次工具失败但恢复。
2. 成功完成任务，没有明显瑕疵。
3. 失败任务，最终回答为空。
4. 重复调用同一个工具。
5. 包含 `resume_` 续跑和子 Agent 的任务。

每条路径都验证：

- 第一屏快速结果。
- 深度复盘结果。
- 证据可点击。
- 页面刷新可恢复。
- 报告不包含敏感信息。

## 18. 验收标准

### 18.1 用户流程验收

- [ ] 用户刚完成一轮任务后能看到“复盘这次任务”。
- [ ] 点击后默认选中当前用户轮次。
- [ ] 面板能展示 Run、模型、耗时、Token、工具数和 Artifact 数。
- [ ] 点击开始后能看到分阶段进度。
- [ ] 3 秒左右能看到确定性体检。
- [ ] 深度复盘完成后能看到分维度质量结论。
- [ ] 每条结论能追溯到具体证据。
- [ ] Judge 不可用时明确显示“未验证”。
- [ ] 页面刷新后能继续查看结果。

### 18.2 正确性验收

- [ ] 失败 Run 不会被显示为完成。
- [ ] 工具失败、重复调用、预算超限来自确定性规则。
- [ ] LLM 不能修改确定性失败结论。
- [ ] 没有验收标准时不会伪造“完成”。
- [ ] 续跑 Run 被正确聚合。
- [ ] 子 Agent 的工具失败进入汇总。
- [ ] Artifact 缺失能被识别。

### 18.3 安全验收

- [ ] Python 不连接 Java 数据库。
- [ ] 导出 Trace 已脱敏。
- [ ] 非 owner 无法访问。
- [ ] 报告和日志不含认证信息。
- [ ] 原始 Trace 不进入 Git。
- [ ] Judge 只接收必要证据。

### 18.4 稳定性验收

- [ ] 双击开始不会创建重复任务。
- [ ] 服务重启后可以从已保存 Trace 恢复。
- [ ] Judge 超时不会丢失快速报告。
- [ ] 浏览器刷新不会丢失任务。
- [ ] 大 Trace 会降级而不是让页面卡死。

## 19. 不在 MVP 范围内

- Codex 与 Cute Agent 的双向对照。
- 批量 Benchmark 运行。
- 自动生成 Badcase 并进入回归。
- 每日定时线上采样。
- 自动修改 Agent 代码或 Prompt。
- 参考轨迹库。
- 多 Judge 投票和人工一致性校准。
- Artifact 全文质量评分。
- 线上 leaderboard 或公开成绩。

这些能力可以在 MVP 稳定后逐步接入，但不能阻塞本次使用流程。

## 20. 风险和待确认项

### 风险一：测试 Trace 导出代码不能直接用于生产

当前导出器位于测试模块，且会拒绝 `dev/prod` profile。

处理：

- 抽取生产导出服务。
- 保留测试 CLI 作为运维入口。
- 不在 Controller 中直接调用测试类。

### 风险二：交互式导出可能超时

处理：

- 使用运行中的 Java 应用内查询，不启动新进程。
- 设置 20MB 和 2000 步限制。
- 超限时降级为快速摘要。

### 风险三：没有明确验收标准

处理：

- 区分“执行成功”和“任务完成”。
- 无标准时标记 `unknown`。
- LLM 推断结论标记 advisory。

### 风险四：LLM 误判工具选择

处理：

- 结论必须引用证据。
- 不显示绝对总分。
- 允许用户打开原始步骤复核。
- 后续用人工标注做校准。

### 风险五：用户轮次包含多个 Run

处理：

- 前端使用 turn group 收集 requestId。
- Java 校验同一会话和归属。
- 报告按 Run 展示，同时给出轮次汇总。

### 待确认项

1. Python Task Review 服务是开发环境功能，还是正式产品功能。
2. 默认 Judge 配置使用哪个模型和 endpoint。
3. 复盘记录是否按用户隔离，还是本机共享。
4. 是否允许 Judge 读取 Artifact 内容；MVP 建议只读元数据。
5. 任务目录默认保留 30 天是否合适。

## 21. 推荐实施顺序

严格按以下顺序推进：

1. 先确定报告 schema 和 Fixture。
2. 再做 Java preflight 和 trace export。
3. 再做 Python 快速体检。
4. 快速体检稳定后再接 LLM。
5. 最后接前端。

不要先做 UI 再补后端契约，否则容易出现：

- 前端字段和后端报告不一致。
- 为了页面效果伪造完成度。
- LLM 输出没有证据引用。
- 大 Trace 在页面上卡死。

## 22. 最终交付效果

功能完成后，用户只需要：

```text
完成一轮对话
  -> 点击“复盘这次任务”
  -> 确认本轮 Run
  -> 点击“开始复盘”
  -> 先看快速体检
  -> 再看 AI 深度复盘
  -> 点开证据查看具体步骤
```

用户最终拿到的是：

- 一个明确的执行状态。
- 一组可信的确定性指标。
- 一份带证据的路径质量复盘。
- 一份可执行的改进建议。
- 一个不会把“主观判断”伪装成“绝对最优”的评测产品。

## 23. 本轮实施状态

本轮已经完成单次任务复盘 MVP 的主链路：

- Java 只读 preflight 和脱敏 Trace Bundle 导出。
- Python 任务创建、查询、取消、快速体检、LLM 深度复盘和本地报告存储。
- 任务级 JSON Schema。
- 对话助手消息工具栏入口。
- `/task-review` 复盘记录页和 `/task-review/:jobId` 详情页。
- 证据展开、分维度质量结论、改进建议和 advisory 标识。
- 服务重启后从已保存 Trace 恢复深度复盘。

本轮明确保留为后续项：

- 真正的请求级幂等合并；目前仍通过前端禁用按钮避免重复点击。
- 完整的历史任务列表导航入口。
- Artifact 全文质量评分。
- 多 Judge 校准和进入 CI 门禁。
- 历史参考轨迹库和 Agent 对照。

验证命令和结果见项目最终交付说明；Judge 未配置时，快速体检仍应可用，深度复盘应显示
`unverified`，不能伪造分数。
