# 工具 Observation Token 收敛二期修复计划

> 状态：已实施（核心投影器、回归测试与定向验证完成）
> 日期：2026-11-17
> 项目：`D:\Java Code\ai-agent\Cute-agent`
> 前置工作：`TOOL_OBSERVATION_TOKEN_BUDGET_PLAN.md` 已完成 Bash、Code Execution、host_cli、browser、agent_browser 五个代码型工具的第一轮收敛
> 本文目标：把当前仍未接入专用 observation 投影器的工具逐项收敛，消除字段级冗余、重复产物信息和失败路径完整 JSON

---

## 1. 背景与结论

第一轮改造已经解决了最明显的几类问题：

- Bash / Code Execution 的失败结果不再自动回退完整 JSON。
- host_cli / browser / agent_browser 的耗时、同步状态、文件大小、重复 URL 等字段不再默认进入模型。
- 浏览器截图中的 base64 不再进入模型 observation。
- 所有最终 observation 统一经过一次 `ToolObservationBudget`。

但当前 `ToolObservationFormatter` 只覆盖了部分工具。未注册专用投影器的工具仍走下面这条路径：

```text
ToolResultPayload
  -> llmObservation 为空
  -> ToolObservationFormatter.format(...) 返回 null
  -> ToolObservationSerializer.serializePayload(...)
  -> 完整 llmData JSON
  -> ToolObservationBudget 仅在超过 30KB 时落盘/截断
```

这带来两个问题：

1. **30KB 不是“小”**。一次工具结果 10KB、20KB 的 JSON 仍会反复进入后续上下文，尤其是多轮 Agent 会话。
2. **预算不能消除结构性冗余**。耗时、文件大小、MIME、URL 别名、成功提示、输入回显等字段即使只有几 KB，也会每轮重复占用 Token。

因此二期不再只关注“结果是否超过上限”，而是为每个高价值工具定义**模型真正需要的字段集合**。

---

## 2. 修复原则

### 2.1 通道边界不变

本次仍然只修改模型可见的 `llmObservation`：

| 通道 | 用途 | 本次是否改变 |
| --- | --- | --- |
| `llmObservation` | 下一轮模型实际看到的文本 | 是 |
| `llmData` | 工具返回的完整结构化载荷 | 否 |
| `toolResult` | 完整 JSON，供账本、回放、前端和排障 | 否 |
| 前端工具卡片 | 用户界面展示 | 否 |
| SSE / TimelineItem | 实时事件协议 | 否 |

硬性要求：

- 不删除 `llmData` 字段。
- 不删除 `toolResult` 字段。
- 不改变工具参数 schema。
- 不改变前端展示字段。
- 不改变历史回放数据。
- 不因为投影器异常而让工具执行失败；异常时仍安全回退。

### 2.2 一个 observation 只保留一份文件清单

当前存在两套产物展示路径：

1. 工具自己的 `llmData.produced_files` / `artifacts`。
2. `BaseAgent.attachToolArtifactSummary()` 追加的“关联文件”摘要。

对于 `deep_search` 和所有文档生成工具，第二套摘要已经存在，第一套再输出一次就会重复。

二期规则：

```text
同一个 observation 中，同一个文件最多出现一次。
```

具体策略：

- 已在 `TOOLS_REQUIRING_LLM_ARTIFACT_SUMMARY` 中的工具，优先使用 artifact 摘要，不在投影器中重复输出文件清单。
- 不在该集合中的工具，由投影器直接输出相对路径，避免模型完全看不到产物。
- 不允许依赖“文本包含文件名”这种脆弱判断做去重；应在投影器层明确选择唯一来源。

### 2.3 成功态少说，失败态保留可诊断信息

成功时模型通常只需要：

- 实际业务结果；
- 必要的警告；
- 产物相对路径；
- 下游工具明确依赖的一个 URL（仅在兼容例外中保留）。

失败时模型需要：

- 稳定错误码；
- 可读错误原因；
- 必要的 stdout / stderr / traceback；
- 可执行的重试或修复提示；
- 受影响的产物路径。

### 2.4 输入回显默认丢弃

以下字段通常是模型刚传入的参数，不应再次返回：

- `task`
- `prompt`
- `query`
- `server`
- `uri`
- `tool`
- `ok`

例外：当工具需要确认“实际生效的参数被规范化过”时，可以保留一个短提示，而不是完整回显。

### 2.5 不为了省 Token 删除语义

以下内容不能因为“可能很长”就直接删除：

- 文档读取的正文 `data` / `content`；
- `code_mode` 的 `result`；
- 搜索结果的标题、URL、摘要；
- 失败日志中的错误行；
- 浏览器页面 `tree`；
- 下游工具依赖的规范 URL。

这些内容应先保留，再通过专用上限或截断策略控制。

---

## 3. 当前工具审计总表

### 3.1 已接入第一轮投影器的工具

以下工具已有专用投影器，本文只处理它们的残留问题：

- `workspace_read`
- `workspace_grep`
- `workspace_glob`
- `workspace_list`
- `workspace_edit`
- `workspace_write`
- `TaskOutput`
- `session_search`
- `host_cli`
- `browser`
- `agent_browser`
- `WebSearch`
- `WebFetch`
- `deep_search`
- `skill_view`
- `skills_search`
- `Agent`
- `SendMessage`
- `TaskStop`
- `TaskCreate`
- `TaskUpdate`
- `TaskGet`
- `TaskList`
- `TodoWrite`
- `EnterPlanMode`
- `ExitPlanMode`
- `AskUserQuestion`
- `RequestDesktopControl`
- `canvas_publish`
- `ToolSearch`
- `ToolDescribe`
- `memory`
- `code_execution`
- `bash`

其中本文仍需处理：

- `deep_search` 的 sources 字段名不一致；
- `Agent` 的 `agentId` 重复输出；
- `memory` 批量结果仍输出完整 JSON 数组。

### 3.2 当前未接入专用投影器的工具

#### P0：条件启用或默认启用，且结果可能明显偏大

| 工具 | 当前状态 | 主要问题 |
| --- | --- | --- |
| `code_mode` | 配置默认关闭，但主 Agent 白名单包含；开启后可返回大量日志和结果 | `duration_ms`、`tool_calls`、`source_hash`、最多 200 条 logs、最大 1MB result |
| `document_generate` | 文档生成组 | 模板、主题、payload、resolved、colors、usage、rendered 等字段全部进入 JSON |
| `slides_generate` | 文档生成组 | 同上 |
| `excel_generator` | 文档生成组 | 同上，且文件元数据与 artifact 摘要重复 |
| `checklist_generate` | 文档生成组 | 同上 |
| `template_filler` | 文档生成组 | 同上 |
| `document_template` | 文档生成组 | 模板定义、变量、resolved 配置可能很大 |
| `theme_designer` | 文档生成组 | 主题、颜色、usage 等内部配置 |
| `chart_generator` | 文档生成组 | chart_type、format、payload、rendered 等 |
| `data_analysis` | 默认工具列表 | `task` 回显、完整 content、重复产物 URL |
| `ListMcpResources` | MCP 资源工具，始终注册 | 最多 100 行，每行 8 个字段，含 mimeType/size/mcpId |

#### P1：默认或条件启用，存在明显冗余

| 工具 | 当前状态 | 主要问题 |
| --- | --- | --- |
| `csv_processor` | 文档读取组 | `data` 外再包 JSON，附 file_size、成功 message |
| `excel_reader` | 文档读取组 | 同上 |
| `html_processor` | 文档读取组 | 同上 |
| `markdown_processor` | 文档读取组 | 同上 |
| `text_processor` | 文档读取组 | 同上 |
| `word_reader` | 文档读取组 | 同上 |
| `pdf_reader` | 文档读取组 | 同上 |
| `pdf_structure` | 文档读取组 | 同上 |
| `citation_extractor` | 文档读取组 | 同上 |
| `image_ocr` | 文档读取组 | 同上 |
| `data_aggregate` | 数据处理组 | 同上 |
| `data_clean` | 数据处理组 | 同上 |
| `data_merge` | 数据处理组 | 同上 |
| `data_transform` | 数据处理组 | 同上 |
| `data_validate` | 数据处理组 | 同上 |
| `sql_query` | 数据处理组 | 同上 |
| `image_generation` | 默认工具列表 | prompt 回显、mode/size/batchCount/mimeType/hint、URL 重复 |
| `ReadMcpResource` | MCP 资源工具 | server/uri 回显、originalChars |

#### P2：低风险小残留或特殊静态内容

| 工具 | 当前状态 | 主要问题 |
| --- | --- | --- |
| `get_ui_components` | openui 工具 | 返回约 13.7KB 静态目录；不是字段冗余，但重复调用会反复占用上下文 |
| `Agent` | 已有投影器 | 后台派发时 `agentId` 输出两次 |
| `memory` | 已有投影器 | 批量操作返回完整 results JSON 数组 |

#### 不在当前主工具池，但代码仍保留

| 工具 | 当前状态 | 处理建议 |
| --- | --- | --- |
| `code_interpreter` | 已从主 Agent 装配移除 | 不纳入当前实施优先级；增加“不得重新注册”的回归测试 |
| `multimodalagent_tool` | 已从主 Agent 装配移除 | 不纳入当前实施优先级；如果未来重新启用，必须复用本文投影协议 |

---

## 4. 工具级详细设计

### 4.1 `code_mode`

#### 4.1.1 当前返回字段

`CodeModeTool.toPayload()` 当前会构造：

```text
ok
duration_ms
tool_calls
produced_files
source_hash
logs
result
error_code
message
```

失败时还会把这些字段放进 `envelope` 作为 `llmData.detail`。

#### 4.1.2 模型真正需要什么

成功时：

- `result`：脚本返回值，通常是模型最需要的结果。
- `produced_files`：写回 workspace 的相对路径。
- `logs`：只在存在 `[warn]` 或 `[error]` 时保留少量诊断行。

失败时：

- `error_code`；
- `message`；
- `logs` 中的错误/警告行；
- 如存在且不与 message 重复，可保留 `result`；
- `produced_files`。

#### 4.1.3 默认丢弃

- `duration_ms`
- `tool_calls`
- `source_hash`
- `ok`
- `tool`
- 成功时的普通 `[log]` / `[info]` 日志
- 与 `result` 重复的文本

#### 4.1.4 建议投影格式

成功：

```text
<result>

files:
- outputs/result.csv

logs (warnings/errors):
[warn] ...
[error] ...
```

失败：

```text
Error: <error_code>: <message>

logs (warnings/errors):
[error] ...

files:
- outputs/partial.csv
```

#### 4.1.5 限制

- `logs` 最多保留 20 行。
- 每行最多 500 字符。
- 日志总量最多 8,000 字符。
- `result` 仍交给统一 observation budget，不单独突破 30KB。
- 如果 `result` 为空且没有错误日志，返回 `(no output)`，不要输出空 JSON。

#### 4.1.6 实施位置

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/ToolObservationFormatter.java`
- 新增 `projectCodeMode(...)`
- 注册 `Map.entry(CodeModeTool.TOOL_NAME, ToolObservationFormatter::projectCodeMode)`
- 将 `code_mode` 加入状态感知工具集合，确保失败时仍走紧凑投影

#### 4.1.7 测试

- 成功 result + 普通 info 日志：不出现 `duration_ms`、`tool_calls`、`source_hash`、普通日志。
- 成功 result + warn/error 日志：保留过滤后的日志。
- 失败：保留 `error_code`、`message`、错误日志，不回退完整 JSON。
- 超长 result：仍经过统一预算，不产生重复落盘。

---

### 4.2 文档生成组

覆盖工具：

- `document_generate`
- `slides_generate`
- `excel_generator`
- `checklist_generate`
- `template_filler`
- `document_template`
- `theme_designer`
- `chart_generator`

#### 4.2.1 当前返回字段

`AbstractDocGenTool.extraLlmData()` 当前会合并：

```text
stats
message
warnings
lint_warnings
templates
template
themes
theme
payload
resolved
colors
usage
saved
deleted
name
kind
path
variables
chart_type
format
rendered
produced_files
```

其中：

- 字符串字段最多 3000 字符；
- `rendered` 最多 2000 字符；
- 但字段数量多，合并后仍可能接近或超过 30KB。

此外，文档生成工具在 `TOOLS_REQUIRING_LLM_ARTIFACT_SUMMARY` 中，`BaseAgent` 还会追加一份“关联文件”摘要。

#### 4.2.2 统一原则

文档生成组不应继续使用“全字段 JSON”作为模型 observation。

建议拆成两类投影：

##### A. 生成类

适用于：

- `document_generate`
- `slides_generate`
- `excel_generator`
- `checklist_generate`
- `chart_generator`

保留：

- `message`：成功时如果是固定成功文案，可省略；失败时必须保留。
- `warnings` / `lint_warnings`：只保留非空项，截断后输出。
- 产物相对路径：由 `BaseAgent` 的 artifact 摘要提供，投影器不重复输出 `produced_files`。

丢弃：

- `stats`
- `templates` / `template`
- `themes` / `theme`
- `payload`
- `resolved`
- `colors`
- `usage`
- `saved`
- `deleted`
- `variables`
- `chart_type`
- `format`
- `rendered`
- `produced_files` 中的 `file_size`
- 所有重复 URL

##### B. 模板/主题类

适用于：

- `document_template`
- `theme_designer`
- `template_filler`

这些工具的返回值可能本身就是模板、主题或填充结果，不能一律删除 `rendered` / `name` / `path`。

建议保留：

- `message`；
- `name`；
- `kind`；
- `path`；
- `warnings` / `lint_warnings`；
- 如果 `rendered` 是该工具的**主要输出**，保留截断后的 `rendered`；
- 产物相对路径由 artifact 摘要提供。

建议丢弃：

- `stats`
- `payload`
- `resolved`
- `colors`
- `usage`
- `saved`
- `deleted`
- `variables`
- 与主要输出重复的 `templates` / `themes`
- 文件大小、MIME、URL 别名

#### 4.2.3 建议投影格式

生成成功：

```text
<message，仅在有业务含义时>

warnings:
- ...

关联文件：
- filePath:outputs/report.docx fileName:report.docx fileDesc:...
```

生成失败：

```text
Error: <message>

warnings:
- ...
```

模板/主题成功：

```text
<message>
name: <name>
kind: <kind>
path: <path>

<rendered，仅在它是主要输出时>

关联文件：
- ...
```

#### 4.2.4 去重要求

因为文档生成组已经在 `TOOLS_REQUIRING_LLM_ARTIFACT_SUMMARY` 中：

- 投影器不再输出 `produced_files`；
- 不在投影器里再拼 `files:`；
- 不允许同时出现两段“关联文件”或“files”。

#### 4.2.5 实施位置

- `ToolObservationFormatter`
- `AbstractDocGenTool`
- `AbstractDocRemoteTool.buildLlmData`
- 测试：`DocToolRemoteProtocolTest`

#### 4.2.6 测试

- 每个生成工具成功时都不出现 `templates`、`payload`、`resolved`、`colors`、`usage`、`rendered`。
- 模板/主题类工具在 `rendered` 是主要输出时仍能看到截断后的正文。
- 警告和 lint 警告非空时保留。
- 产物只出现一次，且与 artifact 摘要不重复。
- 失败时保留错误信息和必要警告。

---

### 4.3 文档读取组与数据处理组

覆盖工具：

- `csv_processor`
- `excel_reader`
- `html_processor`
- `markdown_processor`
- `text_processor`
- `word_reader`
- `pdf_reader`
- `pdf_structure`
- `citation_extractor`
- `image_ocr`
- `data_aggregate`
- `data_clean`
- `data_merge`
- `data_transform`
- `data_validate`
- `sql_query`

#### 4.3.1 当前返回字段

公共基类 `AbstractDocReadTool.extraLlmData()` 当前会返回：

```text
tool
ok
data
message
produced_files
  - file_name
  - file_size
```

其中 `data` 可能是：

- 字符串，最多 12,000 字符；
- 结构化对象；
- 超限后的 JSON 字符串。

#### 4.3.2 模型真正需要什么

- `data`：读取/处理结果正文，必须保留。
- 截断提示：如果 `data` 被截断，必须保留可执行的续读提示。
- 产物相对路径：如果生成了文件，必须保留。
- 失败原因：必须保留。

#### 4.3.3 默认丢弃

- `tool`
- `ok`
- 成功时的 `message`（例如“处理完成”）
- `produced_files[].file_size`
- 重复文件元数据
- 完整 JSON 外层包裹

#### 4.3.4 建议投影格式

成功且 `data` 是字符串：

```text
<data 正文>

files:
- outputs/result.xlsx
```

成功且 `data` 是对象：

```text
<data 的 JSON 表示>

files:
- outputs/result.xlsx
```

失败：

```text
Error: <message>

<可选 data>
```

#### 4.3.5 实施方式

新增统一投影器：

```text
projectDocRead(data)
```

注册到上述 16 个工具名。由于这些工具不在 `TOOLS_REQUIRING_LLM_ARTIFACT_SUMMARY` 中，投影器必须自己输出一次产物相对路径。

#### 4.3.6 测试

- 字符串 `data` 原样输出，不出现 JSON 外层字段。
- 结构化 `data` 仍可被模型读取。
- 成功 `message` 不出现。
- `file_size` 不出现。
- 产物相对路径出现且只出现一次。
- `data` 截断时保留截断提示。
- 失败时保留错误和必要正文。

---

### 4.4 `data_analysis`

#### 4.4.1 当前返回字段

```text
tool
ok
task
content
produced_files
  - file_name
  - url
```

#### 4.4.2 保留

- `content`：分析结果正文，必须保留。
- 产物相对路径。
- 如果下游 `document_generate` 需要，保留一个规范 URL。

#### 4.4.3 丢弃

- `tool`
- `ok`
- `task`：模型输入回显。
- 重复 URL 别名。
- 文件大小、MIME。

#### 4.4.4 建议投影格式

```text
<content>

files:
- outputs/analysis.xlsx
- outputs/chart.png (url: https://...)
```

#### 4.4.5 注意

当前 `DataAnalysisTool` 只保留 `url`，没有 `relative_path`。投影器应优先使用：

1. `relative_path`
2. `file_name`

如果两者都没有，才回退到 URL，避免模型只看到长链接。

#### 4.4.6 测试

- `task` 不出现。
- `content` 完整保留。
- 文件相对路径出现。
- 多个 URL 别名只保留一个。
- 失败时保留错误，不回退完整 JSON。

---

### 4.5 `image_generation`

#### 4.5.1 当前返回字段

```text
tool
ok
summary
prompt
mode
size
batchCount
produced_files
  - file_name
  - mimeType
  - url
hint
```

#### 4.5.2 保留

- `summary`：生成结果摘要。
- 产物相对路径。
- 一个规范 URL：兼容 `document_generate` 的 `image.url`。

#### 4.5.3 丢弃

- `tool`
- `ok`
- `prompt`：输入回显。
- `mode`
- `size`
- `batchCount`
- `mimeType`
- `hint`：固定提示，应由工具描述或 system prompt 承担。
- 重复 URL。

#### 4.5.4 建议投影格式

```text
<summary>

files:
- outputs/generated.png (url: https://...)
```

#### 4.5.5 测试

- `prompt`、`mode`、`size`、`batchCount`、`mimeType`、`hint` 不出现。
- 摘要和文件路径出现。
- 下游需要的规范 URL 仍存在。

---

### 4.6 `ListMcpResources`

#### 4.6.1 当前返回字段

每个 resource 当前返回：

```text
uri
name
title
description
mimeType
size
server
mcpId
```

最多 100 条。

#### 4.6.2 保留

- `uri`：后续 `ReadMcpResource` 需要。
- `name`：人类可读名称。
- `server`：后续读取需要。
- `description`：截断后保留，帮助模型判断是否读取。
- 总数和截断提示。

#### 4.6.3 丢弃

- `title`：与 `name` 重复时丢弃；只有 `name` 为空时才回退使用。
- `mimeType`
- `size`
- `mcpId`：如果 `server` 已经足够定位；如果二者语义不同，保留 `server`，不保留两个定位键。

#### 4.6.4 建议投影格式

```text
resources (37):
- [server] name — uri
  description: ...

[truncated: showing 100 of 237]
```

#### 4.6.5 测试

- 100 条资源不会输出 800 个 JSON 字段。
- `uri`、`name`、`server` 保留。
- `mimeType`、`size`、`mcpId` 不出现。
- `title` 与 `name` 重复时不出现。
- 截断提示保留。

---

### 4.7 `ReadMcpResource`

#### 4.7.1 当前返回字段

```text
tool
ok
server
uri
content
truncated
originalChars
```

#### 4.7.2 保留

- `content`
- 截断标记

#### 4.7.3 丢弃

- `tool`
- `ok`
- `server`
- `uri`
- `originalChars`

#### 4.7.4 建议投影格式

```text
<content>

[content truncated at 40000 chars; use a narrower resource or pagination if available]
```

#### 4.7.5 测试

- 正文原样输出。
- `server`、`uri`、`originalChars` 不出现。
- 截断时保留提示。
- 失败时保留错误。

---

### 4.8 `get_ui_components`

#### 4.8.1 当前情况

该工具返回约 13.7KB 的静态组件目录，内容本身是模型需要的签名，不是字段冗余。

#### 4.8.2 不建议的处理

- 不要盲目截断目录正文，否则模型会缺少组件签名。
- 不要把它改成普通 JSON 字段，当前纯文本更适合模型阅读。

#### 4.8.3 建议处理

作为 P2 观察项：

1. 先统计真实会话中该工具被调用的频率。
2. 如果同一会话重复调用，考虑在会话级缓存或提示模型中“已加载过，无需重复调用”。
3. 如果目录继续增长，再考虑按组件类别拆分查询，而不是简单截断。

本阶段不修改其内容。

---

### 4.9 `Agent`

#### 4.9.1 当前残留

后台派发时当前会输出：

```text
agentId: ...
(agent_id: '...'; result will be returned automatically ...)
```

同一个 ID 出现两次。

#### 4.9.2 建议

- 只保留一次 `agentId`。
- 保留“如何续聊”的可执行提示。
- `run_in_background` 可以保留一个布尔标记。
- `status` 只在非完成态保留。
- `tool_uses` 对主 Agent 决策价值低，建议删除。

#### 4.9.3 建议格式

```text
<content>

agentId: <id> (use SendMessage with to: '<id>' to continue this agent)
run_in_background: true
```

#### 4.9.4 测试

- 后台派发只出现一次 agentId。
- 非完成态仍能看到状态。
- 完成态不再输出工具调用次数。

---

### 4.10 `memory`

#### 4.10.1 当前情况

批量写入时，`results` 被 `JSON.toJSONString(items)` 整体输出。每个操作都包含：

```text
success
staged
no_change
message
used_chars
limit_chars
```

操作数量多时结构重复明显。

#### 4.10.2 建议格式

```text
<message>

- op 1: success; <message>
- op 2: failed; <message>

[staged]
```

容量信息只在接近上限或失败时输出：

```text
usage: 8500/10000
```

#### 4.10.3 测试

- 单条操作不再输出完整 JSON。
- 批量操作每条一行。
- `used_chars` / `limit_chars` 只在有意义时出现。
- `staged` / `no_change` 标记保留。

---

### 4.11 `deep_search` 的 sources 字段修正

#### 4.11.1 当前问题

`DeepSearchStructuredResultBuilder` 生成 observation doc 时写入字段 `link`：

```java
.link(StringUtils.defaultString(rawDoc.getLink()))
```

但 `ToolObservationFormatter.appendSearchResults()` 读取的是 `url`：

```java
String url = text(d.get("url"));
```

结果是来源链接可能被静默丢弃。

#### 4.11.2 修复

优先读 `link`，兼容旧字段 `url`：

```text
link = firstNonBlank(d.get("link"), d.get("url"))
```

#### 4.11.3 测试

- `link` 字段能正常出现在 sources。
- 旧数据中的 `url` 仍能显示。
- 链接为空时不输出空行。

---

## 5. 实施阶段

### Phase 0：补充基线和回归测试

目标：先锁定当前行为，避免修改后无法判断是“省 Token”还是“丢信息”。

工作：

1. 为每个目标工具准备最小成功/失败 fixture。
2. 记录修改前 `llmObservation` 的字符数和字段集合。
3. 为 `toolResult` 增加断言：完整 JSON 字段不减少。
4. 为前端/回放相关字段增加断言：本次不改动。

验证：

- 新测试在当前代码上应能暴露冗余字段。
- 测试不能依赖真实网络、真实 MCP 或真实浏览器。

### Phase 1：P0 投影器

顺序：

1. `code_mode`
2. 文档生成组
3. `data_analysis`
4. `ListMcpResources`

验证：

- 成功/失败都走专用投影。
- 失败不回退完整 JSON。
- 产物只出现一次。

### Phase 2：P1 投影器

顺序：

1. 文档读取组 + 数据处理组
2. `ReadMcpResource`
3. `image_generation`

验证：

- 正文保留。
- 文件大小、MIME、成功提示、参数回显消失。
- 相对路径仍可定位。

### Phase 3：低风险残留

顺序：

1. `Agent` 的 agentId 去重
2. `memory` 批量结果压缩
3. `deep_search` 的 link 字段修正

验证：

- 续聊句柄仍可用。
- memory 的 staged/no_change 语义仍可判断。
- deep_search 来源链接不丢失。

### Phase 4：静态目录与遗留工具

1. 统计 `get_ui_components` 调用频率，不盲目截断。
2. 为 `code_interpreter` / `multimodalagent_tool` 增加“当前不得注册”的回归测试。
3. 如果未来重新启用，要求先接入本文的投影协议。

### Phase 5：真实会话采样

在测试环境或真实会话中采样以下工具：

- `code_mode` 成功/失败
- `document_generate` 成功/失败
- `excel_generator` 成功
- `pdf_reader` 成功
- `data_analysis` 成功/失败
- `image_generation` 成功
- `ListMcpResources`
- `ReadMcpResource`

记录：

- 修改前后 observation 字符数；
- 模型是否仍能完成后续步骤；
- 是否出现文件找不到、URL 丢失、错误不可诊断。

不承诺固定百分比；以实际样本为准。

---

## 6. 测试清单

### 6.1 投影器单元测试

建议扩展：

- `Cute-agent-domain/src/test/java/org/wwz/ai/test/domain/ToolObservationFormatterTest.java`
- 新增 `ToolObservationPhase2Test`（如果现有测试类过大）

覆盖：

| 工具 | 成功 | 失败 | 截断 | 产物 |
| --- | --- | --- | --- | --- |
| `code_mode` | 是 | 是 | 是 | 是 |
| 文档生成组 | 是 | 是 | 是 | 是 |
| 文档读取组 | 是 | 是 | 是 | 是 |
| 数据处理组 | 是 | 是 | 是 | 是 |
| `data_analysis` | 是 | 是 | 是 | 是 |
| `image_generation` | 是 | 是 | 否 | 是 |
| `ListMcpResources` | 是 | 是 | 是 | 否 |
| `ReadMcpResource` | 是 | 是 | 是 | 否 |
| `Agent` | 是 | 是 | 否 | 否 |
| `memory` | 是 | 是 | 否 | 否 |
| `deep_search` | 是 | 是 | 是 | 是 |

### 6.2 Pipeline 测试

扩展：

- `Cute-agent-domain/src/test/java/org/wwz/ai/domain/agent/runtime/agent/ToolExecutionPipelineObservationTest.java`

断言：

- 预填 observation 仍只经过一次 budget。
- 新增投影器失败时仍安全回退。
- `failed=true` 的目标工具不回退完整 JSON。
- 未注册工具行为不变。

### 6.3 工具自身测试

- `DocToolRemoteProtocolTest`：文档生成/读取字段投影。
- `DataAnalysisToolPayloadTest`：`task` 回显、content、产物路径。
- `ImageGenerationToolTest`：摘要、URL、丢弃字段。
- `CodeModeToolIntentTest` 或新增 `CodeModeObservationTest`：result/logs/error。
- `AgentToolCollectionFactoryTest`：确认 `code_interpreter` / `multimodalagent_tool` 仍未注册。

### 6.4 通道隔离测试

至少覆盖：

- `payload.getToolResult()` 仍包含完整 JSON。
- `payload.getLlmData()` 字段未减少。
- `llmObservation` 不含被丢弃字段。
- 前端事件和回放测试不回归。

---

## 7. 验收标准

### 7.1 功能验收

- 模型仍能判断工具成功/失败。
- 失败时仍能看到错误原因和必要诊断。
- 文档读取正文、代码执行结果、搜索摘要不丢失。
- 模型仍能找到产物文件。
- `document_generate` 需要的规范图片 URL 仍可用。
- `Agent` 的续聊 agentId 仍可用。
- `deep_search` 的来源链接不再丢失。

### 7.2 Token 验收

- 目标工具不再默认输出完整 JSON。
- 成功态不出现耗时、调用次数、源码 hash、文件大小、MIME、重复 URL。
- 输入回显字段不出现在 observation。
- 文档生成组不再同时输出 `produced_files` 和“关联文件”。
- `ListMcpResources` 不再对每条资源输出 8 个字段。
- `ReadMcpResource` 不再回显 server/uri。

### 7.3 稳定性验收

- 投影器异常时工具执行不失败。
- 所有最终 observation 仍只执行一次 budget。
- 未覆盖工具行为不变。
- 前端、账本、回放、SSE 不减少字段。
- 定向测试通过，受影响模块编译通过。

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 文档生成 `rendered` 被误删 | 模型看不到模板/主题实际输出 | 对模板/主题类保留“主要输出”例外 |
| 文档读取 `data` 被截断过度 | 模型无法分析正文 | 保留现有 12KB 上限，不额外缩小 |
| 删除 URL 导致 `document_generate` 失败 | 图片无法被下游使用 | 保留每个文件一个规范 URL |
| 产物清单重复未解决 | 省下的 Token 又被 artifact 摘要抵消 | 明确规定唯一文件清单来源 |
| 失败日志被删太多 | 模型无法诊断 | 失败时保留 error/warn 行和稳定错误码 |
| 投影器抛异常 | 工具结果不可用 | 保持现有 catch + 回退路径 |
| 误改 `toolResult` | 前端/回放回归 | 测试同时断言完整载荷不变 |

回退方式：

- 每个工具投影器独立注册，可按工具回退。
- 如果某类文档工具出现兼容问题，先回退该工具投影器，不影响其他工具。
- 不修改 `llmData`，因此回退不需要数据迁移。

---

## 9. 非目标

- 不修改任何工具的入参 schema。
- 不修改工具调用 UI。
- 不修改 SSE、TimelineItem、Execution Ledger。
- 不压缩 `toolResult`。
- 不改变 30KB / 20KB observation budget 的全局策略。
- 不重新设计 `document_generate` 的 URL 协议。
- 不清理历史 MCP 工具。
- 不处理真实 MCP 服务端的返回格式。
- 不自动删除历史沙箱。
- 不对 `get_ui_components` 做未经测量的截断。

---

## 10. 建议提交拆分

1. `test(tool-observation): 增加二期工具观察基线`
2. `feat(tool-observation): 收敛 code_mode 观察`
3. `feat(tool-observation): 收敛文档生成工具观察`
4. `feat(tool-observation): 收敛 data_analysis 与 MCP 资源观察`
5. `feat(tool-observation): 收敛文档读取与数据处理观察`
6. `feat(tool-observation): 收敛 image_generation 与 memory 观察`
7. `fix(deep-search): 修复 sources link 字段丢失`
8. `test(tool-observation): 验证 toolResult 与回放通道不变`

每个提交必须满足：

- 修改代码行直接对应本文中的字段规则。
- 不混入无关重构。
- 定向测试通过。
- 受影响模块编译通过。

---

## 11. 实施前需要确认的问题

1. `document_generate`、`image_generation`、`data_analysis` 的产物 URL 是否仍被真实下游使用；如果不再使用，可进一步改成纯路径方案。
2. `document_template` / `theme_designer` / `template_filler` 的 `rendered` 是否确实是主要输出，还是只是调试副本。
3. `ListMcpResources` 的 `server` 与 `mcpId` 是否总是等价；如果不等价，需要保留哪个作为 `ReadMcpResource` 的稳定定位键。
4. `code_mode` 的普通 info 日志是否需要在成功时保留；当前建议只保留 warn/error。
5. `memory` 的 `used_chars` / `limit_chars` 是否对模型决策有价值；当前建议只在接近上限或失败时保留。

---

## 12. 成功判据

完成后，目标工具在模型侧应呈现为：

```text
代码编排：
  result + 必要错误日志 + 产物路径

文档生成：
  业务消息 + 警告 + 唯一产物清单

文档读取：
  正文 + 截断提示 + 产物路径

数据分析：
  分析正文 + 产物路径 + 必要的一个 URL

图片生成：
  摘要 + 产物路径 + 必要的一个 URL

MCP 资源：
  资源名 + URI + 服务名 + 截断描述

后台 Agent：
  结果 + 一次 agentId + 可执行续聊提示
```

---

## 13. 实施结果（2026-11-18）

### 13.1 已完成

- `code_mode` 已接入专用投影：保留 result、warn/error 日志与产物路径，丢弃耗时、调用次数、源码 hash 和普通日志。
- 文档生成组已接入投影：成功态丢弃模板、主题、payload、resolved、colors、usage、rendered 等内部字段；模板/主题类保留主要输出 `rendered`。
- 文档读取组与数据处理组共 16 个工具已接入统一投影：保留正文 `data`，丢弃外层 JSON、成功提示和文件大小。
- `data_analysis` 已接入投影：保留 content 与规范文件 URL，丢弃 task 回显。
- `image_generation` / `image_generation_tool` 已接入投影：保留摘要与规范 URL，丢弃 prompt、mode、size、batchCount、mimeType、hint。
- `ListMcpResources` / `ReadMcpResource` 已接入投影：资源列表压缩为单行条目，正文读取丢弃 server/uri 回显。
- `Agent` 后台派发只输出一次 agentId，并移除 `tool_uses`；`memory` 批量结果改为逐条紧凑输出。
- `deep_search` sources 同时兼容 `link` 与旧 `url`。
- 失败态白名单已扩展到本期所有目标工具，避免失败时回退完整 JSON。

### 13.2 回归验证

已通过：

- `mvn -pl Cute-agent-domain -Dtest=ToolArtifactFormatterTest,ToolObservationFormatterTest,ToolExecutionPipelineObservationTest,ToolObservationBudgetTest,ToolObservationSerializerTest test -DskipTests=false`：82 个测试通过。
- `mvn -pl Cute-agent-domain -Dtest=DocToolRemoteProtocolTest,DeepSearchFileNameTest test -DskipTests=false`：24 个测试通过。
- `mvn -pl Cute-agent-app -am -Dtest=DataAnalysisToolPayloadTest,ImageGenerationToolTest,AgentToolCollectionFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test -DskipTests=false`：37 个测试通过。
- `mvn -pl Cute-agent-app -am -Dtest=DeepSearchLlmObservationTest,CodeModeToolIntentTest -Dsurefire.failIfNoSpecifiedTests=false test -DskipTests=false`：5 个测试通过。

完整 Domain 套件共运行 1146 个测试，本次相关测试全部通过；仅剩两个既有无关失败：

- `WorkingMemoryCompactionEventTest.oversizedPayloadsAreTruncatedAtConstruction`
- `SubAgentRunnerLifecycleTest.cancelledRunReturnsFailedInsteadOfCompleted`

### 13.3 未改变

- `llmData`、`toolResult`、structured output 未删字段。
- 前端工具卡片、SSE、TimelineItem、Execution Ledger 未改动。
- 工具入参 schema 未改动。
- 产物完整 URL 与文件元数据仍保留在完整载荷中；模型侧只保留一个规范引用。

而以下内容只保留在 `llmData` / `toolResult`：

```text
tool
ok
duration_ms
tool_calls
source_hash
prompt
task
mode
size
batchCount
mimeType
file_size
usage
resolved
payload
colors
originalChars
重复 URL
```
