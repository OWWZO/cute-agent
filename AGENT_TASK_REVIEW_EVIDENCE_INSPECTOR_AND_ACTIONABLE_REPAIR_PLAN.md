# Agent 任务复盘：证据详情与可执行修复单开发计划

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档状态 | 待实施 |
| 适用产品 | Cute Agent 单次任务复盘 / Task Review |
| 主要目标 | 点击证据直接查看工具入参和出参；把开发者行动项升级为可复制给 Coding Agent 的详细修复单 |
| 当前基线 | `task-review.v2`、确定性失败归因、Judge Pass 1、综合报告 Pass 2、证据引用链 |
| 关联文档 | `AGENT_TASK_REVIEW_FAULT_ATTRIBUTION_AND_REPORT_PLAN.md`、`AGENT_LLM_JUDGE_OPTIMIZATION_PLAN.md` |

---

## 2. 一句话结论

这次要做两个增强：

1. **证据可视化**

   用户点击：

   ```text
   tool:1794819108266-9502:call_00_xxx
   ```

   不再只是跳到时间线，而是打开一个证据详情面板，直接展示：

   - 工具名。
   - 步骤编号。
   - 调用状态。
   - 完整入参。
   - 完整出参。
   - 错误消息。
   - 调用耗时。
   - 恢复调用。
   - 关联产物。

   这些内容全部由系统从 Execution Ledger Trace 自动解析，**不允许裁判生成或补写工具入参、出参**。

2. **行动项可执行化**

   当前的：

   > 调整 Prompt、停止条件和策略切换规则。

   要升级为：

   > 修复 `code_execution` 工具在参数解析失败后仍继续执行、且错误没有归一为可恢复错误码的问题。
   >
   > 增加调用前 URL/参数校验；在 executor 边界把底层异常转换为结构化 `MODEL_ARGUMENT`；失败后禁止相同参数自动重试；补充单元测试和回放测试。

   最终每条行动项要包含：

   - 具体 Bug 标题。
   - 问题定义。
   - 证据摘要。
   - 最小复现。
   - 根因假设。
   - 修改范围。
   - 实施步骤。
   - 测试计划。
   - 验收标准。
   - 可直接复制给 Coding Agent 的完整 Prompt。

---

## 3. 当前存在的问题

### 3.1 证据引用只有 ID，没有详情

当前失败归因里有：

```json
{
  "evidenceRefs": [
    "tool:1794819108266-9502:call_00_evbFUryT1gORJydhQYT84474"
  ]
}
```

前端将它们渲染为锚点链接。

点击后只能跳到时间线附近：

- 入参和结果位于折叠区域。
- 长 JSON 不易查看。
- 无法快速确认失败发生在哪一步。
- 无法直接比较失败调用和恢复调用。
- 同一工具多次失败时，定位不够明确。

### 3.2 入参和出参容易被多个地方重复拼装

当前数据存在：

- `timeline.steps[].toolCalls`
- `timeline.tools[]`
- `attribution.evidenceRefs`

如果每个前端组件各自查找，会出现：

- 引用解析规则不一致。
- 一部分证据能打开，一部分不能。
- 旧任务引用格式不兼容。
- 截断和脱敏规则不一致。

因此必须建立统一的服务端证据解析器。

### 3.3 开发者行动项仍停留在“责任层建议”

当前行动项示例：

```text
P1 MODEL_DECISION：调整 Prompt、停止条件和策略切换规则（本次主责任 1 次）
P0 HARNESS_RUNTIME：检查执行器异常、超时、结果丢失和状态上报
```

问题是：

- 没有指出具体是哪一个工具。
- 没有指出具体失败现象。
- 没有引用具体调用。
- 没有复现步骤。
- 没有说明代码应该改哪里。
- 没有测试要求。
- Coding Agent 拿到后仍需要重新推理。

### 3.4 当前行动项按责任层聚合，粒度太粗

同一个 `HARNESS_RUNTIME` 下可能包含：

- 工具超时。
- 序列化失败。
- 执行器崩溃。
- 结果写入丢失。

它们不是同一个 Bug，不能用一条“检查执行器异常”覆盖。

正确聚合键应为：

```text
primaryLayer + toolName + failureKind + ruleIds + 错误签名
```

---

## 4. 目标与非目标

### 4.1 目标

#### 目标 A：工具证据一键查看

- 点击任意工具证据，打开详情面板。
- 入参、出参、错误和元数据由服务端自动补齐。
- 详情必须来自当前 Job 保存的 Trace。
- 不依赖 Judge 输出。

#### 目标 B：统一证据解析

- 支持新格式：

  ```text
  tool:<requestId>:<toolCallId>
  ```

- 兼容旧格式：

  ```text
  tool:<toolCallId>
  ```

- 引用缺失时返回明确错误，不静默展示错误内容。

#### 目标 C：行动项按具体 Bug 聚合

- 每类失败生成一个独立行动项。
- 行动项必须写明工具、失败类型、规则和证据。
- 具有最小复现、修复步骤和验证标准。

#### 目标 D：生成可直接交给 Coding Agent 的 Prompt

- 每个行动项包含 `agentPrompt`。
- `agentPrompt` 由代码模板生成。
- 用户可一键复制。
- Prompt 包含问题定义、证据、修改要求、测试要求和禁止事项。

#### 目标 E：报告同步升级

- 前端展示详细行动项。
- 下载的 Markdown 包含详细行动项。
- 相关证据引用在报告中可追溯。

### 4.2 非目标

- 不读取或展示模型隐藏推理。
- 不让 Judge 生成工具原始入参或出参。
- 不让 Judge 修改 Trace。
- 不在本阶段自动修改代码。
- 不在本阶段自动提交 Git。
- 不读取任意本地文件来补证据。
- 不承诺每一条根因都是最终真相。
- 无法定位代码时，不伪造文件路径和类名。

---

## 5. 设计原则

### 5.1 证据事实与裁判解释分离

#### 事实层

由系统提供：

- 工具名。
- 入参原文。
- 入参解析结果。
- 出参原文。
- 出参来源。
- 状态。
- 错误消息。
- 时间。
- 恢复调用。
- 关联产物。

#### 解释层

由 Judge 提供：

- 失败责任层。
- 原因解释。
- 恢复质量。
- 行动建议。

Judge 只能返回：

```json
{
  "evidenceRefs": [
    "tool:request-id:call-id"
  ]
}
```

Judge 不能返回：

- 伪造的入参。
- 伪造的出参。
- 伪造的错误消息。

### 5.2 服务端解析优先

证据引用到详情的解析必须统一放在 Python 复盘后端。

前端不做核心解析，只负责：

- 显示。
- 复制。
- 搜索。
- 折叠和展开。

### 5.3 行动项必须可验证

每条修复单至少满足：

- 能说明“哪里错了”。
- 能说明“为什么错”。
- 能说明“怎么复现”。
- 能说明“改哪里”。
- 能说明“改完怎么验证”。

如果证据不足，只能输出：

```text
TRACE_GAP：先补齐证据
```

不能输出模糊的代码修改建议。

### 5.4 不虚构代码位置

当系统知道内置工具与代码模块的映射时，可以给出模块提示。

当系统不知道：

- 先写“需要 Coding Agent 在仓库中搜索”。
- 给出搜索关键词。
- 给出精确类名或工具名。
- 不伪造文件路径。

---

## 6. 完整使用流程

### 6.1 用户查看失败归因

页面展示：

```text
browser · 模型参数
步骤 18 · 置信度 86%
原因：模型使用了页面上不存在的选择器。
证据：tool:1794819108266-9502:call_00_xxx
```

### 6.2 点击证据

点击 `tool:...` 后：

1. 前端立即打开证据面板。
2. 如果内存缓存已有，直接显示。
3. 否则请求：

   ```text
   GET /task-review/jobs/{jobId}/evidence?ref=<urlencoded>
   ```

4. 服务端从 Job 中保存的 Trace 解析。
5. 返回系统事实。
6. 前端展示入参和出参。

### 6.3 查看入参

面板显示：

```text
工具：browser
状态：FAILED
步骤：18

入参：
{
  "args": [
    "click",
    "#publish-button"
  ]
}
```

功能：

- 复制原始 JSON。
- 复制格式化 JSON。
- 查看解析错误。
- 查看参数是否被截断。

### 6.4 查看出参

面板显示：

```text
出参来源：toolResult
状态：FAILED

selector not found: #publish-button
```

同时显示：

- 错误消息。
- 耗时。
- 开始时间。
- 结束时间。
- 恢复调用。
- 关联产物。

### 6.5 查看恢复调用

如果一次失败之后出现了成功调用：

```text
失败调用：
tool:req:call-1

恢复调用：
tool:req:call-3
```

点击恢复调用时继续打开同一个证据面板。

### 6.6 查看开发者行动项

页面展示：

```text
P1 · 修复 browser 工具选择器错误不可诊断的问题
失败类型：ARG_SELECTOR_NOT_FOUND
影响：本次出现 3 次，恢复 2 次
```

展开后显示：

- 问题定义。
- 证据摘要。
- 最小复现。
- 根因假设。
- 修复步骤。
- 测试步骤。
- 验收标准。
- 给 Coding Agent 的完整 Prompt。

### 6.7 一键复制给 Coding Agent

点击：

```text
复制修复任务
```

剪贴板中得到完整 Markdown Prompt。

用户可直接粘贴给 Codex、Claude Code 或其他 Coding Agent。

---

## 7. 数据契约

### 7.1 EvidenceDetail

新增 `task-review-evidence.schema.json`。

结构：

```json
{
  "schemaVersion": "1.0",
  "ref": "tool:req-1:call-1",
  "kind": "tool_call",
  "resolved": true,
  "requestId": "req-1",
  "runId": "run-1",
  "toolCallId": "call-1",
  "toolName": "browser",
  "step": 18,
  "status": "FAILED",
  "failed": true,
  "input": {
    "present": true,
    "raw": "{\"args\":[\"click\",\"#publish-button\"]}",
    "parsed": {
      "args": ["click", "#publish-button"]
    },
    "parseError": null,
    "truncated": false
  },
  "output": {
    "present": true,
    "source": "toolResult",
    "raw": "selector not found: #publish-button",
    "parsed": null,
    "truncated": false
  },
  "error": {
    "message": "selector not found: #publish-button",
    "code": null,
    "class": "ARG_SELECTOR_NOT_FOUND"
  },
  "timing": {
    "startedAt": "2026-11-16T18:10:00",
    "finishedAt": "2026-11-16T18:10:01",
    "durationMs": 1000
  },
  "context": {
    "subAgentId": null,
    "subAgentType": null,
    "dispatchIndex": 0,
    "llmInvocationId": 42
  },
  "recovery": {
    "recovered": true,
    "refs": [
      "tool:req-1:call-3"
    ]
  },
  "artifacts": [],
  "limitations": []
}
```

字段约束：

- `ref` 必须存在。
- `kind` 首期只支持 `tool_call`。
- `resolved=false` 时不得返回伪造的入参或出参。
- `input.parsed` 可以是对象、数组、字符串、数字、布尔或 null。
- `input.parseError` 非空时保留原始文本。
- `output.source` 取值：

  ```text
  toolResult
  llmObservation
  missing
  ```

- `truncated=true` 时必须在前端显示提示。

### 7.2 EvidenceRef 规范

新格式：

```text
tool:<requestId>:<toolCallId>
```

示例：

```text
tool:1794819108266-9502:call_00_evbFUryT1gORJydhQYT84474
```

兼容格式：

```text
tool:<toolCallId>
```

解析顺序：

1. 精确匹配 `requestId + toolCallId`。
2. 旧格式按 `toolCallId` 匹配。
3. 如果旧格式命中多个 Run，返回 `AMBIGUOUS_EVIDENCE_REF`。
4. 未命中返回 `EVIDENCE_NOT_FOUND`。

### 7.3 DeveloperActionPlan

行动项结构升级：

```json
{
  "actionId": "action-001",
  "title": "修复 browser 工具选择器错误不可诊断的问题",
  "targetLayer": "MODEL_ARGUMENT",
  "priority": "P1",
  "actionScope": "TOOL_IMPLEMENTATION",
  "status": "ready",
  "bugSignature": "MODEL_ARGUMENT|browser|ARG_SELECTOR_NOT_FOUND",
  "frequency": {
    "count": 3,
    "recovered": 2,
    "unrecovered": 1
  },
  "problemDefinition": {
    "observed": "browser 在 3 次调用中使用了不存在的选择器。",
    "expected": "调用前应获得可用元素证据，失败时返回可操作的选择器错误。",
    "impact": "导致输入失败、重复调用和执行时间增加。"
  },
  "evidenceRefs": [
    "tool:req:call-1",
    "tool:req:call-2",
    "tool:req:call-3"
  ],
  "reproduction": {
    "preconditions": [
      "页面中存在可交互输入框",
      "模型使用了不存在的 selector"
    ],
    "steps": [
      "执行同样的 browser click 调用",
      "观察工具是否返回 selector not found",
      "检查 Agent 是否重复相同参数"
    ],
    "expectedFailure": "工具返回不可操作的 selector not found，Agent 继续重复调用。"
  },
  "rootCauseHypothesis": {
    "summary": "参数生成前缺少页面观察；工具错误未提示可用元素。",
    "confidence": 0.86,
    "alternatives": [
      "页面在调用前发生变化",
      "工具返回了错误的选择器语法"
    ]
  },
  "affectedAreas": [
    {
      "kind": "TOOL",
      "name": "browser"
    },
    {
      "kind": "PROMPT",
      "name": "browser action policy"
    }
  ],
  "fixPlan": [
    {
      "order": 1,
      "summary": "复现并锁定失败",
      "detail": "增加 selector 不存在时的失败测试。"
    },
    {
      "order": 2,
      "summary": "改善工具错误",
      "detail": "返回 errorCode、失败 selector 和可用元素摘要。"
    }
  ],
  "testPlan": {
    "unit": [
      "selector 不存在时返回 ARG_SELECTOR_NOT_FOUND"
    ],
    "integration": [
      "相同参数第二次失败后触发诊断或策略切换"
    ],
    "regression": [
      "有效 selector 的正常点击不受影响"
    ]
  },
  "acceptanceCriteria": [
    "首次失败可读取明确 errorCode",
    "相同参数不会连续调用超过一次",
    "后续成功调用可被记录为恢复证据"
  ],
  "agentPrompt": "# 修复任务\n..."
}
```

### 7.4 actionScope

新增行动范围：

```text
PRODUCT_RUNTIME
TOOL_IMPLEMENTATION
MODEL_POLICY
ENVIRONMENT_CONFIG
TRACE_INSTRUMENTATION
USER_WORKSPACE
NO_ACTION
```

说明：

| actionScope | 用途 |
| --- | --- |
| `PRODUCT_RUNTIME` | Cute Agent 执行器、Agent Loop、状态机问题 |
| `TOOL_IMPLEMENTATION` | 某个内置工具的实现问题 |
| `MODEL_POLICY` | Prompt、停止条件、重试策略、参数生成策略 |
| `ENVIRONMENT_CONFIG` | 登录、网络、沙箱、浏览器配置 |
| `TRACE_INSTRUMENTATION` | Trace 结果缺失、状态源不完整 |
| `USER_WORKSPACE` | 用户工作区项目自身的代码或数据问题 |
| `NO_ACTION` | 用户主动停止、正常行为或没有可执行修复 |

### 7.5 兼容字段

继续保留：

```json
{
  "target": "MODEL_ARGUMENT",
  "priority": "P1",
  "action": "...",
  "expectedEffect": "...",
  "evidenceRefs": []
}
```

新字段为增量扩展，旧任务和旧前端仍可读取。

---

## 8. 后端证据解析器

### 8.1 新增模块

建议新增：

```text
evals/agent/src/cute_evals/task_review/evidence.py
evals/agent/src/cute_evals/task_review/evidence_index.py
```

职责：

- 建立引用索引。
- 解析工具调用。
- 脱敏。
- 截断。
- 解析 JSON。
- 查找恢复调用。
- 关联产物。

### 8.2 索引构建

在复盘 Job 创建时构建轻量索引：

```json
{
  "tool:req-1:call-1": {
    "requestId": "req-1",
    "runId": "run-1",
    "toolCallId": "call-1",
    "index": 0
  }
}
```

索引只保存定位信息，不复制完整入参和出参。

### 8.3 详情解析

`resolve_evidence(bundle, ref)` 执行：

1. 解析引用。
2. 找到对应 Run。
3. 找到工具调用。
4. 解析 `inputJson`。
5. 读取 `toolResult`。
6. 如果 `toolResult` 为空，回退 `llmObservation`。
7. 读取 `errorMsg`。
8. 查找恢复调用。
9. 查找同一 `toolCallId` 的产物。
10. 执行脱敏。
11. 执行长度截断。
12. 返回 EvidenceDetail。

### 8.4 入参解析

规则：

```text
inputJson 为空       -> present=false
合法 JSON 对象       -> parsed=object
合法 JSON 数组       -> parsed=array
合法 JSON 标量       -> parsed=scalar
非法 JSON            -> parsed=null, parseError=具体错误
```

原始文本始终保留到配置上限。

### 8.5 出参解析

优先级：

```text
toolResult
llmObservation
missing
```

如果出参看起来是 JSON：

- 尝试解析为对象。
- 保留原始文本。

如果解析失败：

- 不报错。
- 作为文本展示。

### 8.6 恢复调用

使用现有 `detect_recovery()` 结果。

返回：

- 恢复引用。
- 恢复类型。
- 恢复说明。

### 8.7 关联产物

按 `toolCallId` 查找：

```text
artifact.toolCallId == tool.toolCallId
```

返回：

- `relativeRef`
- MIME 类型。
- 文件大小。
- Hash。
- 是否可预览。

### 8.8 脱敏与截断

复用并扩展现有脱敏规则：

- Bearer Token。
- API Key。
- Cookie。
- Password。
- 本地绝对路径。
- 内网 URL。
- 用户隐私字段。

默认上限：

```text
入参：24,000 字符
出参：24,000 字符
单个证据总计：48,000 字符
```

超限时：

- 保留头部和尾部。
- `truncated=true`。
- 返回截断长度。

---

## 9. 后端 API

### 9.1 查询单个证据

```text
GET /task-review/jobs/{jobId}/evidence?ref=<urlencoded evidenceRef>
```

成功：

```json
{
  "schemaVersion": "1.0",
  "ref": "tool:req:call",
  "kind": "tool_call",
  "resolved": true
}
```

错误：

| Code | 状态码 | 含义 |
| --- | --- | --- |
| `INVALID_EVIDENCE_REF` | 400 | 引用格式非法 |
| `EVIDENCE_NOT_FOUND` | 404 | 当前 Job 中不存在 |
| `AMBIGUOUS_EVIDENCE_REF` | 409 | 旧格式命中多个工具调用 |

### 9.2 Job 内轻量索引

在 Job 响应中增加：

```json
{
  "evidenceIndex": {
    "tool:req:call-1": {
      "toolName": "browser",
      "step": 18,
      "status": "FAILED"
    }
  }
}
```

用途：

- 前端点击后立即显示标题与加载状态。
- 减少用户等待感。
- 不把完整入参和出参塞进主 Job 响应。

### 9.3 兼容旧 Job

旧 Job 没有 `evidenceIndex` 时：

1. 前端从 `fast.runs[].timeline.tools` 临时构建轻量索引。
2. 点击后仍调用证据详情 API。
3. 服务端从已保存的 `trace/bundle.json` 解析。

---

## 10. 详细修复单生成

### 10.1 聚合并发

不再按责任层生成一条通用建议。

聚合键：

```text
primaryLayer
+ toolName
+ failureKind
+ ruleIds 排序
+ 归一化错误签名
```

示例：

```text
MODEL_ARGUMENT|browser|ARG_SELECTOR_NOT_FOUND|selector
```

同一组失败合并为一个行动项。

### 10.2 错误签名归一化

对错误消息：

1. 小写。
2. 删除动态 ID。
3. 删除 URL 查询参数。
4. 删除时间戳。
5. 删除随机文件名。
6. 保留稳定错误短语。

示例：

```text
selector #post-123 not found
selector #post-456 not found
```

归一化后：

```text
selector <dynamic> not found
```

### 10.3 行动项生成顺序

```text
1. 读取失败归因
2. 按失败签名分组
3. 统计次数、恢复次数、未恢复次数
4. 选择 Playbook
5. 合并确定性事实
6. 生成问题定义
7. 生成复现步骤
8. 生成修复步骤
9. 生成测试计划
10. 生成验收标准
11. 使用代码模板生成 agentPrompt
12. 由 Judge 仅补充解释和建议
13. 校验证据引用
```

### 10.4 行动项详细度要求

每条行动项至少包含：

- 具体标题。
- 失败发生的工具。
- 失败类型。
- 失败次数。
- 恢复次数。
- 至少一条证据。
- 观测到的现象。
- 期望行为。
- 最小复现。
- 根因假设。
- 修改模块或搜索入口。
- 至少 2 个实施步骤。
- 至少 1 个单元测试。
- 至少 1 个集成或回归测试。
- 可验证的验收标准。
- 完整 `agentPrompt`。

缺少任意关键字段时：

```text
status = needs_more_evidence
```

不能标记为 `ready`。

### 10.5 不允许的行动项

以下内容不允许作为最终行动项：

```text
检查工具异常
优化 Prompt
增加错误处理
提升稳定性
再测试一下
```

除非它们后面明确写出：

- 哪个工具。
- 哪个错误。
- 哪条规则。
- 哪个模块。
- 哪个测试。

---

## 11. Action Playbook

### 11.1 MODEL_DECISION：失败后重复相同调用

触发：

```text
ARG_REPEATED_AFTER_FAILURE
```

行动项标题：

```text
修复 <toolName> 失败后重复提交相同参数的问题
```

修复方向：

1. 在 Agent Loop 中维护最近一次失败调用的：

   - 工具名。
   - canonical input hash。
   - failure signature。

2. 第二次相同调用前强制：

   - 重新读取错误。
   - 检查参数是否变化。
   - 如果未变化，生成诊断提示。

3. 连续第 N 次相同调用：

   - 触发策略切换。
   - 或终止当前子路径。

测试：

- 相同参数连续失败两次时，不进行第三次原样调用。
- 改变参数后允许继续。
- 切换到其他工具后可以成功。

### 11.2 MODEL_ARGUMENT：选择器不存在

触发：

```text
ARG_SELECTOR_NOT_FOUND
```

修复方向：

1. 调用前要求先执行 `observe` 或读取可用元素。
2. 工具错误返回：

   - `errorCode=ARG_SELECTOR_NOT_FOUND`
   - 失败 selector。
   - 可用元素摘要。
   - 推荐下一步。

3. Prompt 明确禁止：

   - 凭猜测生成 selector。
   - 未观察页面时直接批量点击。

### 11.3 MODEL_ARGUMENT：路径不存在

触发：

```text
ARG_PATH_NOT_FOUND
```

修复方向：

1. 调用前检查工作区路径。
2. 统一相对路径规范。
3. 错误返回候选路径。
4. 禁止把绝对路径和远端路径混用。
5. 如果是同步缺口，转为 `ENVIRONMENT` 或 `HARNESS_RUNTIME`。

### 11.4 MODEL_ARGUMENT：URL 或协议错误

触发：

```text
ARG_URL_INVALID
```

修复方向：

1. Schema 增加 URL 格式约束。
2. 调用前执行协议校验。
3. 错误消息返回字段名和非法值摘要。
4. 不允许底层异常直接穿透成模糊报错。

### 11.5 HARNESS_CONTRACT：工具错误不可操作

触发：

```text
FAIL_SCHEMA_CONTRACT
HARNESS_ERROR_NOT_ACTIONABLE
```

修复方向：

1. 定义统一错误结构：

   ```json
   {
     "ok": false,
     "errorCode": "...",
     "message": "...",
     "field": "...",
     "expected": "...",
     "nextAction": "..."
   }
   ```

2. 禁止只返回：

   ```text
   unknown error
   operation failed
   ```

3. 保持错误码稳定。
4. 测试错误序列化。

### 11.6 HARNESS_RUNTIME：超时

触发：

```text
FAIL_TIMEOUT
```

修复方向：

1. 为工具配置独立超时。
2. 超时触发取消。
3. 输出稳定 `TIMEOUT` 状态。
4. 记录当前阶段。
5. 只允许幂等操作自动重试。
6. 非幂等操作禁止自动重试。

### 11.7 HARNESS_RUNTIME：执行器异常

触发：

```text
FAIL_EXECUTOR_EXCEPTION
```

修复方向：

1. 在执行器边界捕获异常。
2. 保留原始 cause。
3. 映射为结构化错误码。
4. 保证 ledger 写入失败状态。
5. 保证不会误报 SUCCESS。

### 11.8 TRACE_GAP：结果缺失

触发：

```text
FAIL_RESULT_MISSING
```

修复方向：

1. 工具执行后必须在同一事务内写入：

   - status
   - result
   - error
   - statusSource
   - finishedAt

2. 增加“无结果但有状态”的测试。
3. 增加“异常后仍写入 finally”的测试。

### 11.9 ENVIRONMENT：登录或网络

触发：

```text
FAIL_AUTH
FAIL_NETWORK
```

修复方向：

1. 错误分类为环境问题。
2. 返回可操作提示。
3. 不把环境问题归因给模型。
4. 增加登录态和网络健康检查。

### 11.10 USER_ACTION

不生成代码修复项。

显示：

```text
用户主动停止，不是 Agent 失败。
```

### 11.11 UNKNOWN

不生成猜测性修复。

生成：

```text
补齐 Trace 证据
```

行动项必须说明缺少哪类证据：

- 入参。
- 结果。
- 错误消息。
- 状态。
- 时间。
- 产物。

---

## 12. Judge Pass 2 的职责边界

### 12.1 Judge 可以做什么

- 总结问题。
- 解释多个证据之间的关系。
- 提出更优顺序。
- 补充修复建议。
- 生成更适合人类阅读的描述。

### 12.2 Judge 不可以做什么

- 改写工具入参。
- 补写工具出参。
- 编造错误消息。
- 编造文件名。
- 编造测试结果。
- 删除确定性证据。
- 把 `UNKNOWN` 强行改成某个责任层。

### 12.3 合并规则

```text
确定性字段优先
Judge 只能填充建议性字段
agentPrompt 由代码模板生成
```

如果 Judge 输出与确定性字段冲突：

1. 保留确定性字段。
2. 把冲突写入 `limitations`。
3. 行动项降级为 `needs_review`。

---

## 13. 前端设计

### 13.1 证据按钮

将当前：

```text
<a href="#tool-...">
```

改为：

```text
<button type="button">
```

样式保持苹果风：

- 轻量胶囊。
- 等宽字体显示引用 ID。
- 右侧小箭头。
- Hover 显示“查看工具调用”。

### 13.2 EvidenceInspectorDialog

新增：

```text
ui/src/components/TaskReview/EvidenceInspectorDialog.tsx
```

使用现有 `Dialog` 组件。

桌面端：

- 位于右侧。
- 宽约 `760px`。
- 高度接近视口。
- 圆角 `28px`。
- 半透明背景和模糊遮罩。

移动端：

- 接近全屏。
- 入参和出参上下排列。

### 13.3 首屏内容

首屏直接显示：

- 工具名。
- 状态。
- 步骤。
- 耗时。
- 错误摘要。

不要先显示“正在加载完整详情”超过 1 秒。

### 13.4 入参和出参布局

桌面端：

```text
左：入参
右：出参
```

移动端：

```text
上：入参
下：出参
```

每侧都支持：

- 自动换行。
- 横向滚动。
- 复制。
- 下载 JSON。
- 截断提示。

### 13.5 证据解析状态

加载中：

- 显示索引中的工具名和步骤。
- 入参/出参显示骨架屏。

加载失败：

- 显示 API 错误。
- 回退到时间线中的 `argumentsPreview` 和 `resultPreview`。
- 明确标记“当前为预览，不是完整证据”。

### 13.6 证据链跳转

面板底部展示：

```text
失败调用 -> 恢复调用
```

点击后切换详情。

### 13.7 行动项卡片

行动项默认显示：

- 优先级。
- 标题。
- 影响工具。
- 失败类型。
- 次数。

展开后显示：

- 问题定义。
- 证据。
- 复现。
- 根因。
- 修复步骤。
- 测试。
- 验收标准。

按钮：

```text
复制修复任务
查看证据
展开完整 Prompt
```

### 13.8 复制内容

复制按钮写入剪贴板的内容必须包含：

- 背景。
- 问题定义。
- 证据摘要。
- 原始错误。
- 最小复现。
- 修改要求。
- 测试要求。
- 验收标准。
- 禁止事项。

不能只复制一句“优化错误处理”。

---

## 14. Coding Agent Prompt 模板

`agentPrompt` 由代码生成，建议模板：

```markdown
# 修复任务：<具体标题>

## 背景

你在维护 Cute Agent 的任务执行链路。
本次问题来自真实 Execution Ledger Trace，不是假设场景。

## 问题定义

- 工具：`<toolName>`
- 失败类型：`<failureKind>`
- 主责任层：`<primaryLayer>`
- 贡献责任层：`<layers>`
- 发生次数：`<count>`
- 未恢复次数：`<unrecovered>`

## 观测到的现象

<problemDefinition.observed>

## 期望行为

<problemDefinition.expected>

## 证据

<evidence summaries>

## 最小复现

<reproduction steps>

## 根因假设

<rootCauseHypothesis>

## 修改要求

1. 先搜索并定位实现，不要直接假设文件路径。
2. 先写一个能复现问题的失败测试。
3. 修复根因，不要只吞异常。
4. 保持错误码和状态结构化。
5. 不修改无关代码。
6. 不自动提交 Git。

## 建议检查区域

<affectedAreas>

## 测试要求

<unit/integration/regression>

## 验收标准

<acceptanceCriteria>

## 禁止事项

- 不得伪造成功。
- 不得把异常改成空结果。
- 不得静默重试非幂等操作。
- 不得删除现有失败证据。
- 不得只修改报告文案而不修复运行时行为。

## 交付格式

请输出：

1. 根因确认。
2. 修改文件列表。
3. 关键 diff 说明。
4. 测试命令与结果。
5. 仍存在的风险。
```

---

## 15. Markdown 报告升级

### 15.1 开发者行动项章节

从：

```markdown
- P1 MODEL_ARGUMENT：补充参数示例并在调用前执行 Schema 校验。
```

升级为：

```markdown
### P1 修复 browser 工具选择器错误不可诊断的问题

**问题定义**

browser 在 3 次调用中使用了不存在的 selector。

**证据**

- `tool:req:call-1`
- `tool:req:call-2`

**最小复现**

1. 打开目标页面。
2. 调用 browser click。
3. 使用不存在的 selector。

**修复方案**

1. 增加 selector 错误码。
2. 返回失败 selector。
3. 增加可用元素摘要。
4. 阻止相同参数重复调用。

**测试**

- 单元测试：错误码。
- 集成测试：恢复调用。
- 回归测试：正常 selector。

**验收**

- 首次失败可诊断。
- 相同参数不连续重试。
```

### 15.2 完整 Prompt

报告末尾可选择性包含：

````markdown
## 可复制给 Coding Agent 的修复任务

```text
<agentPrompt>
```
````

---

## 16. 文件级改动清单

### 16.1 Python 新增

| 文件 | 用途 |
| --- | --- |
| `evals/agent/src/cute_evals/task_review/evidence.py` | 证据详情解析 |
| `evals/agent/src/cute_evals/task_review/evidence_index.py` | 引用索引 |
| `evals/agent/src/cute_evals/task_review/action_playbooks.py` | 按失败类型生成修复策略 |
| `evals/agent/src/cute_evals/task_review/action_compiler.py` | 聚合、生成结构化行动项和 agentPrompt |
| `evals/agent/schemas/task-review-evidence.schema.json` | 证据详情 Schema |

### 16.2 Python 修改

| 文件 | 改动 |
| --- | --- |
| `evals/agent/src/cute_evals/task_review/facts.py` | 构建轻量 evidenceIndex |
| `evals/agent/src/cute_evals/task_review/jobs.py` | 保存索引、提供证据查询 |
| `evals/agent/src/cute_evals/task_review/judge.py` | 合并结构化行动项 |
| `evals/agent/src/cute_evals/task_review/narrative.py` | 报告和 Markdown 输出详细行动项 |
| `evals/agent/src/cute_evals/service/app.py` | 新增 evidence API |
| `evals/agent/schemas/task-review-narrative.schema.json` | 扩展 DeveloperActionPlan |
| `evals/agent/schemas/task-review.schema.json` | 增加 evidenceIndex |

### 16.3 Java 修改

本阶段原则上不改 Java。

原因：

- 当前 Trace 已包含工具名、入参、出参、错误、状态、耗时和产物关联。
- 证据详情由已经导出的 Trace 解析。

只有发现字段缺失时才增加：

- `errorCode`
- `statusSource`
- `inputParseError`
- `resultPresent`

### 16.4 前端新增

| 文件 | 用途 |
| --- | --- |
| `ui/src/components/TaskReview/EvidenceInspectorDialog.tsx` | 证据详情面板 |
| `ui/src/components/TaskReview/DeveloperActionCard.tsx` | 可执行修复单 |

### 16.5 前端修改

| 文件 | 改动 |
| --- | --- |
| `ui/src/components/TaskReview/TaskReviewReport.tsx` | 证据按钮改为打开详情面板 |
| `ui/src/components/TaskReview/NarrativeReportPanel.tsx` | 展示详细行动项 |
| `ui/src/services/taskReview.ts` | 增加 EvidenceDetail 类型和 API |

---

## 17. 实施阶段

### Phase 0：契约与 Fixture

交付：

- EvidenceDetail Schema。
- DeveloperActionPlan 扩展 Schema。
- 4 个真实工具证据 Fixture。
- 6 类行动项 Fixture。

验收：

- 所有 Fixture 通过 Schema。
- 旧 `narrative` 仍能读取。

### Phase 1：证据解析器

交付：

- `evidence.py`
- `evidence_index.py`
- JSON 解析。
- 入参/出参来源选择。
- 恢复调用。
- 产物关联。
- 脱敏与截断。

验收：

- 当前截图中的 4 个 ref 都能解析。
- 入参和出参与 Trace 完全一致。
- 不调用 Judge。

### Phase 2：证据 API

交付：

- `GET /task-review/jobs/{jobId}/evidence`
- 错误码。
- 旧引用兼容。
- 轻量 evidenceIndex。

验收：

- 合法 ref 返回 200。
- 非法 ref 返回 400。
- 不存在返回 404。
- 歧义旧引用返回 409。
- 响应含截断和脱敏提示。

### Phase 3：行动项编译器

交付：

- 失败签名归一化。
- 失败分组。
- Playbook。
- 问题定义。
- 复现步骤。
- 修复步骤。
- 测试计划。
- 验收标准。
- `agentPrompt`。

验收：

- 每个 failureKind 有明确 Playbook。
- 同类失败合并。
- 不同工具不混在一起。
- 无证据时输出 `needs_more_evidence`。

### Phase 4：Judge 与报告整合

交付：

- Judge 只能补充解释。
- 行动项合并。
- Markdown 长报告升级。

验收：

- 确定性字段不被 Judge 覆盖。
- 报告包含详细行动项。
- `agentPrompt` 完整且可复制。

### Phase 5：前端证据面板

交付：

- 证据按钮。
- EvidenceInspectorDialog。
- 入参/出参双栏。
- 复制。
- 恢复调用跳转。
- 加载失败回退。

验收：

- 点击证据无需离开页面。
- 入参和出参同时可见。
- 移动端可用。
- 键盘可关闭。

### Phase 6：前端行动项

交付：

- DeveloperActionCard。
- 展开问题定义、复现、修复、测试、验收。
- 复制修复任务。
- 查看关联证据。

验收：

- 不再显示责任层通用套话。
- 每条行动项包含具体工具和错误。
- 复制内容可直接粘贴给 Coding Agent。

### Phase 7：硬化

交付：

- 脱敏测试。
- 超大入参/出参测试。
- 旧 Job 测试。
- 歧义引用测试。
- 真实 Session 回归。

---

## 18. 测试计划

### 18.1 证据解析单元测试

- 新格式 ref。
- 旧格式 ref。
- 非法 ref。
- 不存在 ref。
- 歧义 ref。
- 空入参。
- 非法 JSON 入参。
- 空出参。
- `toolResult` 回退 `llmObservation`。
- 恢复调用。
- 关联产物。

### 18.2 脱敏测试

- Bearer Token。
- API Key。
- Cookie。
- Password。
- 绝对路径。
- 内网 URL。
- 手机号和邮箱。

### 18.3 截断测试

- 24K 入参。
- 24K 出参。
- 总量 48K。
- 头尾都保留。
- 截断标记正确。

### 18.4 行动项测试

- 相同 failureKind 聚合。
- 不同工具不聚合。
- 次数统计正确。
- 恢复次数正确。
- Playbook 命中正确。
- agentPrompt 包含全部必需章节。
- 无证据时降级。

### 18.5 API 测试

- 200。
- 400。
- 404。
- 409。
- Markdown 下载不受影响。

### 18.6 前端测试

- 点击证据打开 Dialog。
- 加载状态。
- 入参显示。
- 出参显示。
- 复制成功。
- 恢复证据跳转。
- API 失败回退时间线预览。
- 行动项展开。
- 复制修复任务。

### 18.7 真实回归

使用当前会话：

```text
1794819108266-9502
```

验证以下引用：

```text
tool:1794819108266-9502:call_00_evbFUryT1gORJydhQYT84474
tool:1794819108266-9502:call_00_1vWsLg98HlgrUu1kxDJU3779
tool:1794819108266-9502:call_00_VFQSy5NvUino2xkUGz655695
tool:1794819108266-9502:call_00_KlxXpkpA2GZEs9C84UbX5314
```

检查：

- 入参正确。
- 出参正确。
- 错误正确。
- 行动项不再泛泛而谈。

---

## 19. 验收标准

### 19.1 证据详情

- 点击任意工具证据都能打开面板。
- 入参和出参来自 Trace。
- 无 Judge 参与。
- 长内容有截断提示。
- 敏感内容已脱敏。
- 当前会话 4 个真实 ref 全部通过。

### 19.2 行动项

- 标题指出具体工具和 Bug。
- 问题定义包含观测与期望。
- 至少一条证据。
- 有最小复现。
- 有根因假设。
- 有至少两个修改步骤。
- 有单元测试和回归测试。
- 有验收标准。
- 有可复制 `agentPrompt`。

### 19.3 可交给 Coding Agent

将 `agentPrompt` 粘贴给 Coding Agent 后，它不需要重新向用户询问：

- 哪个工具。
- 哪个错误。
- 如何复现。
- 改什么行为。
- 跑什么测试。

### 19.4 兼容与安全

- 旧 Job 可继续查看。
- 旧证据引用可解析。
- 不泄漏密钥、Cookie、绝对路径。
- 不影响官方 benchmark 分数。

---

## 20. 风险与缓解

### 风险一：证据面板数据重复

缓解：

- 主 Job 只包含轻量索引。
- 完整入参和出参按需查询。

### 风险二：超大工具结果影响页面

缓解：

- 服务端截断。
- 前端折叠。
- 显示截断提示。

### 风险三：旧引用歧义

缓解：

- 不猜测。
- 返回候选列表。
- 前端要求用户选择。

### 风险四：行动项虚构文件位置

缓解：

- 代码模板生成。
- 无模块映射时只给搜索关键词。
- 明确“先定位，再修改”。

### 风险五：Coding Agent 修改范围过大

缓解：

- Prompt 明确禁止修改无关文件。
- 要求先写失败测试。
- 要求列出修改文件和测试结果。

### 风险六：Judge 篡改事实

缓解：

- 事实字段不从 Judge 合并。
- 证据详情只从 Trace 解析。
- 冲突写入限制并降级行动项。

---

## 21. 不在本阶段范围内

- 自动修复代码。
- 自动提交 Git。
- 自动运行用户仓库的任意命令。
- 自动上传证据到外部服务。
- 读取模型隐藏思维链。
- 自动判断所有行动项的最终根因。
- 对所有历史任务重新调用 Judge。

---

## 22. 最终交付效果

用户看到失败归因时会得到：

```text
browser · 模型参数
修复 browser 选择器错误不可诊断的问题

证据：
[tool:1794819108266-9502:call_00_xxx] 点击查看
```

点击后：

```text
工具：browser
步骤：18
状态：FAILED

入参：
{
  "args": ["click", "#publish-button"]
}

出参：
selector not found: #publish-button

恢复调用：
tool:1794819108266-9502:call_00_yyy
```

行动项展开后：

```text
问题定义：
browser 在页面尚未完成渲染时使用了不存在的 selector。

修复方案：
1. 增加 selector 错误码。
2. 返回失败 selector 和可用元素摘要。
3. 阻止相同参数重复调用。
4. 增加失败测试和恢复测试。

[复制修复任务]
```

最终，开发者不再是“看到一个模糊建议”，而是得到一份：

- 有证据。
- 可复现。
- 可修改。
- 可测试。
- 可验收。
- 可直接交给 Coding Agent 的修复任务。
