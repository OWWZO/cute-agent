# 040 · 代码型工具调用意图字段（intent）

> 状态：已实施，自动验证通过，待人工验收
> 日期：2026-11-15
> 适用项目：`D:\Java Code\ai-agent\Cute-agent`
> 覆盖工具：`bash`、`code_execution`、`code_mode`、`browser`、`agent_browser`、`host_cli`
> 目标：让 Agent 在调用不易读的工具时，主动用一句自然语言说明“这次调用要做什么、处理什么对象、希望得到什么结果”

## 0 · 结论

统一给六个代码型工具增加同一个字符串参数 `intent`，并把它加入 JSON Schema 的 `required`。

`intent` 只作为工具入参保存，复用现有 `ai_agent_tool_invocation.input_json`、SSE `input` / `argumentsText` 和默认历史回放链路。不新增数据库列，不新增 SSE 字段，不修改 TimelineItem 协议。

前端优先用 `intent` 作为工具卡摘要；缺失时完全回退到当前展示逻辑。工具执行层不因 `intent` 缺失而拒绝执行，避免一个展示字段阻断实际任务。

## 1 · 背景

以下工具的核心入参是命令、源码或 CLI 参数：

| 工具 | 当前主要入参 | 用户为什么看不出意图 |
| --- | --- | --- |
| `bash` | `command` | 只能看到 shell 命令，不知道为什么要执行 |
| `code_execution` | `source` / `workspaceFile` | Python 源码可能很长，摘要只能截取片段 |
| `code_mode` | `source` / `workspaceFile` | JavaScript 编排逻辑比单条命令更不直观 |
| `browser` | `args` | CLI argv 不表达业务目标 |
| `agent_browser` | `args` | 与 `browser` 相同，也是不透明的浏览器命令 |
| `host_cli` | `tool` + `args` | 第三方 CLI 的 argv 不能直观表达业务目标 |

这些工具与 `read`、`write`、`grep` 等不同：后者从入参通常就能看出动作和目标，前者必须由 Agent 主动补充意图。

本计划只处理上述六个工具，不扩大到其他工具。

## 2 · 字段契约

### 2.1 字段名

统一字段名：`intent`

不使用以下名称：

- 不用 `description`：项目中已有工具说明、文件说明等语义，容易冲突。
- 不用 `summary`：后端 SSE 已有自己的调用摘要。
- 不用 `reasoning` / `thought`：避免诱导模型输出思维链。
- 不用 `goal`：`goal` 更像整轮任务目标，而这里描述的是单次工具调用的目的。

### 2.2 类型和长度

```json
"intent": {
  "type": "string",
  "description": "用一句面向用户的话说明本次调用的目的：要做什么、处理什么对象、希望得到什么结果。不要复述命令或源码，不要写推理过程，不要包含密钥、Cookie 或 Token。",
  "minLength": 1,
  "maxLength": 160
}
```

约束说明：

- Schema 中标记为必填。
- 建议模型写一句自然语言，与当前用户语言保持一致。
- 推荐长度 20～80 个中文字符，硬上限 160。
- 不写换行，不写 Markdown，不写 HTML。
- 不在 `intent` 中复述完整命令或源码。

### 2.3 调用态与执行态分离

- Schema 层：`required` 包含 `intent`，让模型每次生成工具调用时都明确知道需要填写。
- 执行层：不校验、不因空白 `intent` 返回失败，继续执行原工具逻辑。
- 展示层：`intent` 为空或缺失时回退到现有摘要，并保证旧历史记录完整可用。

这样可以兼顾模型约束、旧数据兼容和工具执行稳定性。

### 2.4 示例

```json
{
  "intent": "下载上交所 2024 年报 PDF，供后续提取财务指标",
  "command": "curl -L ... -o report.pdf"
}
```

```json
{
  "intent": "合并订单表和退款记录，计算本月实际销售额",
  "source": "..."
}
```

```json
{
  "intent": "批量打开 20 个商品页，收集价格和库存状态",
  "source": "..."
}
```

```json
{
  "intent": "打开商家后台订单页，读取今天的待发货订单",
  "args": ["browser", "click", "@e12"]
}
```

## 3 · 共享 Schema 工具

新增一个极小的静态工具类：

`Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/ToolCallIntentSchema.java`

职责只有两项：

1. 返回统一的 `intent` 参数定义，避免六个工具各写一份文案后逐渐漂移。
2. 返回统一的工具描述后缀，例如：
   > 调用时必须提供 `intent`，用一句面向用户的话说明本次调用的目的。

不新增接口，不扩展 `BaseTool`，不做不必要的抽象。

## 4 · 六个工具的 Schema 接入

### 4.1 `bash`

文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/shell/BashTool.java`

改动：

- `toParams()` 中最先加入 `intent`，使工具参数顺序更稳定。
- `required` 从 `["command"]` 改为 `["intent", "command"]`。
- 工具描述追加统一意图说明。

### 4.2 `code_execution`

文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/CodeExecutionTool.java`

改动：

- `toParams()` 中加入 `intent`。
- `required` 从 `[]` 改为 `["intent"]`。
- `source` / `workspaceFile` 继续二选一，不加入 `required`。
- 工具描述追加统一意图说明。
- `execute()` 中构造远端请求时显式移除 `intent`，不能把它发给 Python 沙箱。

注意：当前 `CodeExecutionTool.execute()` 会复制整个入参 Map，必须在该处处理，否则新增字段会进入远端请求。

### 4.3 `code_mode`

文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/codemode/CodeModeTool.java`

改动：

- `toParams()` 中加入 `intent`。
- `required` 从 `[]` 改为 `["intent"]`。
- `source` / `workspaceFile` 继续二选一，不加入 `required`。
- 工具描述追加统一意图说明。
- `CodeModeRequest` 继续只接收原有字段，不把 `intent` 传给 GraalJS 执行器。

### 4.4 `browser` 与 `agent_browser`

文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/browser/BrowserCliSupport.java`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/browser/BrowserTool.java`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/browser/AgentBrowserTool.java`

改动：

- 在共用的 `BrowserCliSupport.toParams(...)` 中加入 `intent`。
- `required` 从 `["args"]` 改为 `["intent", "args"]`。
- 两个浏览器工具自动同时获得同一字段，避免只加一个。
- 两个工具描述追加统一意图说明。
- CLI 执行仍只读取 `args` 和 `timeout_ms`，不得把 `intent` 拼入 argv。

### 4.5 `host_cli`

文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/cli/HostCliTool.java`

改动：

- `toParams()` 中加入 `intent`。
- `required` 从 `["tool", "args"]` 改为 `["intent", "tool", "args"]`。
- 工具描述追加统一意图说明。
- `CliInvocation` 继续只接收 `tool`、`args` 和超时等原有字段，不把 `intent` 拼入 argv。

### 4.6 接入结果

| 工具 | `required` |
| --- | --- |
| `bash` | `["intent", "command"]` |
| `code_execution` | `["intent"]` |
| `code_mode` | `["intent"]` |
| `browser` | `["intent", "args"]` |
| `agent_browser` | `["intent", "args"]` |
| `host_cli` | `["intent", "tool", "args"]` |

## 5 · 持久化与回放

### 5.1 不新增数据库字段

`intent` 是工具入参的一部分，已经由现有链路保存：

- `ToolLedgerRecorder` 将完整工具入参写入 `ai_agent_tool_invocation.input_json`。
- 实时 SSE 的 `tool_call` 已携带 `argumentsText`、`argumentsRaw` 和 `input`。
- 默认工具回放会把 `input_json` 还原为 `toolParam`。

因此本计划不修改：

- `ai_agent_tool_invocation` 表结构
- `schema.sql`
- Mapper XML
- `ToolInvocation` 实体
- TimelineItem 协议
- SSE 事件结构

### 5.2 不重复存储

不把 `intent` 再写进 `tool_result`、`llm_observation`、TimelineItem 内容或单独的数据库列。

Execution Ledger 继续是唯一内容来源，`intent` 只从工具入参读取。

### 5.3 旧数据兼容

历史工具调用没有 `intent` 时：

- 后端不需要回填。
- 前端不报错，继续使用当前 `toolSummary` 逻辑。
- 历史回放保持现有视觉和行为。

## 6 · 前端展示

### 6.1 摘要优先级

修改文件：

- `ui/src/components/Dialogue/tools/toolMeta.ts`

六个工具统一按以下优先级显示摘要：

1. `intent`
2. 当前原有摘要：Bash 命令、Code 源码片段、浏览器参数回退等

`toolSummary()` 中的 `bash` 分支需要先读取 `d.intent`。由于 `code_execution` 和 `code_mode` 已归一化为 `bash`，三者会共用这条逻辑。

新增 `browser`、`agent_browser`、`host_cli` 分支，优先显示 `intent`；没有 `intent` 时保持现有回退行为。

### 6.2 展示形态

工具卡折叠状态显示：

- `Bash · 下载 2024 年报 PDF`
- `Code Execution · 清洗订单数据并生成汇总表`
- `Code Mode · 批量采集商品库存`
- `Browser · 读取订单列表数据`

展开后仍显示原有完整命令、源码或 CLI 参数，不删除、不截断底层执行信息。

### 6.3 安全与尺寸

- `intent` 只能作为纯文本渲染，不能按 Markdown、HTML 或富文本解析。
- 沿用现有摘要裁剪长度，避免长文本撑破工具行。
- 不把 `intent` 当作审计、授权或副作用判断依据，它只是调用说明。

### 6.4 参数流式阶段

流式参数尚未形成合法 JSON 时，不强行解析 `intent`，保持当前参数流预览。

参数完整或历史回放时，再按 `intent` 优先展示。

## 7 · 测试计划

### 7.1 后端 Schema 测试

更新或新增：

- `Cute-agent-domain/src/test/java/org/wwz/ai/test/domain/tool/ToolCallIntentSchemaTest.java`
- `Cute-agent-domain/src/test/java/org/wwz/ai/test/domain/tool/CodeExecutionToolSchemaTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/spring/ai/ToolSchemaNormalizerTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/BrowserToolTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/AgentBrowserToolTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/HostCliToolTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/spring/ai/AgentToolCollectionFactoryTest.java`

断言：

- 六个工具的 `properties` 都包含 `intent`。
- 六个工具的 `required` 都包含 `intent`。
- `code_execution` 和 `code_mode` 仍不要求 `source`。
- `browser` 和 `agent_browser` 仍要求 `args`。
- `intent` 经过 `ToolSchemaNormalizer` 后仍保留 `type`、`minLength`、`maxLength`。

### 7.2 参数隔离测试

更新：

- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/CodeExecutionToolRequestTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/BashToolIntentTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/CodeModeToolIntentTest.java`
- `Cute-agent-app/src/test/java/org/wwz/ai/test/domain/HostCliToolTest.java`

断言：

- 调用 `code_execution` 时传入 `intent`，捕获到的远端请求体不包含 `intent`。
- 原有 `source`、`workspaceFile`、`fileNames` 等字段行为不变。

补充：

- `browser` / `agent_browser` 的 CLI argv 不包含 `intent`。
- `host_cli` 的 CLI argv 不包含 `intent`。
- `code_mode` 的 `CodeModeRequest` 不包含 `intent`。

### 7.3 前端摘要测试

更新：

- `ui/src/components/Dialogue/tools/toolMeta.test.ts`

覆盖：

- `bash` 有 `intent` 时优先显示 `intent`。
- `code_execution` 有 `intent` 时优先显示 `intent`。
- `code_mode` 有 `intent` 时优先显示 `intent`。
- `browser` 有 `intent` 时优先显示 `intent`。
- `agent_browser` 有 `intent` 时优先显示 `intent`。
- `host_cli` 有 `intent` 时优先显示 `intent`。
- 没有 `intent` 时，原有摘要完全不变。
- 空白 `intent` 不覆盖原有摘要。

## 8 · 实施顺序

### 步骤 1：建立共享字段定义

- 新增 `ToolCallIntentSchema`。
- 保持类无状态，只返回新的 Map，不共享可变对象。

验证：

- `mvn -pl Cute-agent-domain -am compile -DskipTests`

### 步骤 2：接入六个工具 Schema

- 依次修改 `BashTool`、`CodeExecutionTool`、`CodeModeTool`、`BrowserCliSupport`。
- `browser` 和 `agent_browser` 通过共享 `BrowserCliSupport` 同时生效。
- 修改 `HostCliTool`，补入 `intent`。
- 更新对应的工具描述。

验证：

- 六个工具的 `required` 与第 4.6 节一致。
- 原工具说明中的命令面切换、source/workspaceFile 二选一等语义不变。

### 步骤 3：隔离执行参数

- `code_execution` 远端请求移除 `intent`。
- 检查其余四个工具只读取原有字段，不把 `intent` 传给执行层。

验证：

- 捕获远端请求体和 CLI argv，确认不含 `intent`。

### 步骤 4：接入前端摘要

- `toolMeta.ts` 增加 `intent` 优先级。
- `browser`、`agent_browser` 增加摘要分支。
- `host_cli` 增加摘要分支。
- 不修改工具卡结构、展开区和输出区。

验证：

- `pnpm exec vitest run src/components/Dialogue/tools/toolMeta.test.ts`

### 步骤 5：回归验证

后端：

```powershell
mvn -pl Cute-agent-domain -am test "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ToolCallIntentSchemaTest,CodeExecutionToolSchemaTest"
mvn -pl Cute-agent-app -am test "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ToolSchemaNormalizerTest,BrowserToolTest,AgentBrowserToolTest,HostCliToolTest,CodeExecutionToolRequestTest,AgentToolCollectionFactoryTest,CodeModeToolIntentTest,BashToolIntentTest"
```

前端：

```powershell
cd ui
pnpm exec vitest run src/components/Dialogue/tools/toolMeta.test.ts
pnpm build
```

### 步骤 6：人工验收

- 分别触发 `bash`、`code_execution`、`code_mode`、`browser`、`agent_browser`、`host_cli`。
- 实时卡片摘要应优先显示 `intent`。
- 展开后仍能看到完整命令、源码或 argv。
- 刷新页面后，历史工具卡仍显示同一个 `intent`。
- 打开一个没有 `intent` 的旧会话，展示与当前版本一致。

## 9 · 验收标准

- [ ] 六个工具的工具定义都包含必填 `intent`。
- [ ] `intent` 使用统一文案，不在六个类中复制出不一致的说明。
- [ ] `code_execution` 远端请求不包含 `intent`。
- [ ] 浏览器 CLI argv、Code Mode 执行请求不包含 `intent`。
- [ ] 前端实时和历史回放都优先显示 `intent`。
- [ ] 没有 `intent` 的旧数据不报错、不退化成空白卡。
- [ ] 不新增数据库迁移、SSE 字段或 TimelineItem 字段。
- [ ] 现有工具执行、产物采集、历史回放测试通过。

## 10 · 不做的事

- 不给 `read`、`write`、`grep`、`search` 等其他工具增加 `intent`。
- 不用代码分析自动推断 `intent`。
- 不让 `intent` 参与权限判断或副作用判断。
- 不把 `intent` 写进模型 observation，避免重复内容和额外 token。
- 不修改 Execution Ledger 表结构。
- 不修改 SSE 事件协议。
- 不修改 TimelineItem 协议。
- 不为 `code_mode` 内部的 `code_mode.browser` 等虚拟子调用单独增加 `intent`；外层 `code_mode.intent` 已概括整次编排。

## 11 · 风险与处理

| 风险 | 处理 |
| --- | --- |
| 某些模型仍省略 `intent` | Schema 标记必填，前端保留旧摘要；后续可根据实际采样决定是否补一条全局 Prompt |
| `intent` 被传入沙箱或 CLI | 执行入口显式读取白名单字段；增加参数隔离测试 |
| `intent` 太长撑破工具行 | Schema 限制 160 字符，前端沿用摘要裁剪 |
| `intent` 含 HTML/Markdown | 只按纯文本展示，不使用富文本渲染 |
| 新旧记录展示不一致 | 新记录优先 `intent`，旧记录走原逻辑，不要求回填 |
| 六个工具文案漂移 | 使用一个共享 Schema 工具生成字段定义和描述后缀 |

## 12 · 执行前检查

当前工作区存在大量未提交改动，实施前必须：

1. 运行 `git.exe status --short` 确认目标文件状态。
2. 逐文件读取当前内容，只在现有改动上做最小补充。
3. 不撤销、不覆盖用户已有的未提交修改。
4. 不执行全仓格式化。

## 13 · 实施记录

2026-11-15 已完成代码与自动验证：

- 新增 `ToolCallIntentSchema`，统一 `intent` 字段定义和工具描述。
- `bash`、`code_execution`、`code_mode`、`browser`、`agent_browser`、`host_cli` 均已声明必填 `intent`。
- `code_execution` 远端请求会显式移除 `intent`。
- `browser` / `agent_browser` 的 CLI argv 不包含 `intent`。
- `host_cli` 的 CLI argv 不包含 `intent`。
- `code_mode` 的 `CodeModeRequest` 不包含 `intent`。
- 前端 `toolSummary()` 优先显示 `intent`；空白 `intent` 自动回退。
- 未新增数据库字段、SSE 字段或 TimelineItem 字段。

自动验证结果：

- Domain：`ToolCallIntentSchemaTest`、`CodeExecutionToolSchemaTest` 共 5 个用例通过。
- App：`ToolSchemaNormalizerTest`、`BrowserToolTest`、`AgentBrowserToolTest`、`HostCliToolTest`、`CodeExecutionToolRequestTest`、`AgentToolCollectionFactoryTest`、`CodeModeToolIntentTest`、`BashToolIntentTest` 共 63 个用例通过。
- 前端：`toolMeta.test.ts` 23 个用例通过。
- 前端生产构建：`pnpm build` 通过。

剩余验收：

- 在真实对话中分别触发六个工具，确认实时卡片和历史回放均优先显示 `intent`。
