# Cute-agent 代码型工具运行计划：沙箱身份归位与 Observation Token 收敛

> 状态：两阶段均已实现并通过定向测试；完整领域套件仅剩两个与本次改动无关的既有失败
> 日期：2026-11-17
> 项目：`D:\Java Code\ai-agent\Cute-agent`
> 覆盖范围：
> - 沙箱归属身份：`bash`、`code_execution`
> - Observation Token：`bash`、`code_execution`、`host_cli`、`browser`、`agent_browser`

## 总体结论

这次要解决的是两个相关但不能混为一谈的问题：

1. **沙箱归属身份错误**：`bash` 和 `code_execution` 原来借用长期记忆的 owner 解析方式。子 Agent 为了隔离长期记忆，故意清空了 `ltmOwner`，于是 owner 解析退化成 `session:<子会话ID>`。每个子 Agent 都是独立会话，最终就为同一个真实用户创建了多个 E2B 沙箱。
2. **模型 Observation 过胖**：部分成功结果已经做过压缩，但失败路径、预填 observation、浏览器截图 base64、文件 URL 和同步元数据仍可能把完整 JSON 暴露给大模型，造成不必要的 Token 消耗。

两部分必须按不同原则处理：

- **沙箱归属**使用稳定的用户级身份 `user:<userId>`。
- **长期记忆归属**继续按会话/子 Agent 隔离，不要求和沙箱 owner 相同。
- **子 Agent 的独立续聊**不能只依赖父上下文继承，必须能按 `sessionId` 从数据库恢复真实用户。
- **Observation 收敛**只改变模型看到的 `llmObservation`，不改变完整 `tool_result`、前端卡片、账本和 SSE。

## 实施结果

已完成：

- `SandboxOwnerResolver` 统一 Bash / Code Execution 的用户级沙箱 owner，子 Agent 可从会话账本恢复 `userId`，长期记忆仍隔离。
- `ToolObservationFormatter` 支持五个代码型工具的状态感知 failure projector。
- `ToolExecutionPipeline` 对所有最终 observation 统一执行一次 `ToolObservationBudget`，预填文本不再绕过预算。
- Bash / Code Execution 投影保留 stdout、stderr、非零退出码、错误、必要结果、删除路径、同步冲突和产物路径；成功同步明细与文件元数据不再进入模型。
- Code Execution 为 `document_generate` 兼容保留 `produced_files[].url` 中一个规范 URL。
- host_cli 失败、浏览器失败和敏感命令不再预填完整 JSON；`error_hint`、`retry`、`error_code`、`notice` 和产物失败原因继续保留。
- browser / agent_browser 的模型 observation 会以线性扫描方式移除 `imageBase64` 和 data-image base64，不使用可能栈溢出的正则；完整 `llmData` 仍保留原值。

验证结果：

- 领域层定向测试 69 项通过。
- Bash / Code Execution 额外定向测试 12 项通过。
- 应用层浏览器、CLI、Code Execution、沙箱身份定向测试 48 项通过。
- 完整 `Cute-agent-domain` 套件运行 1129 项，仅 `WorkingMemoryCompactionEventTest` 和 `SubAgentRunnerLifecycleTest` 两个既有失败；相关文件不在本次修改范围。

---

## 第一部分：沙箱归属身份与长期记忆身份解耦

### 1. 问题现象

截图中的现象是：一个真实账户使用 Agent，但 E2B 控制台出现许多沙箱实例。主 Agent、子 Agent、以及子 Agent 被独立打开后的续聊，可能各自命中不同的 ownerKey，因此被 E2B 当成不同用户，反复创建沙箱。

这不是 E2B 自己“按会话建沙箱”，而是 Java 侧传给 E2B 的 `ownerKey` 不稳定。

### 2. 根因

修复前，`BashTool` 和 `CodeExecutionTool` 都使用下面的逻辑：

```java
LtmOwner owner = context.getLtmOwner();
if (owner == null) {
    owner = LtmOwnerResolver.resolve(
            context.getUserId(), null, context.getSessionId());
}
return owner.asOwnerKey();
```

这条逻辑对长期记忆是合理的：没有用户时可以退化成 `session:<sessionId>`，避免匿名调用共享同一份记忆。

但沙箱不是长期记忆：

- `SubAgentContextFactory` 为了让子 Agent 不继承父 Agent 的长期记忆，刻意设置了 `ltmOwner=null`。
- 修复前子上下文又没有稳定写入 `userId`。
- 于是 `LtmOwnerResolver.resolve(null, null, childSessionId)` 返回 `session:<childSessionId>`。
- 每个子 Agent 的 childSessionId 都不同，E2B 的 `UserSandboxManager` 就按不同 ownerKey 各创建一个沙箱。

后端复用规则本身没有问题。`cute-tool/cute_tool/tool/bash_sandbox.py` 已明确说明：E2B 模式由 `UserSandboxManager` 按 ownerKey 复用沙箱，在同一个沙箱内再按 `sessions/{sessionId}/` 隔离工作目录。问题在于上游传进来的 ownerKey 被错误地做成了会话级。

### 3. 必须坚持的身份模型

| 身份 | 作用 | 是否允许会话级 |
| --- | --- | --- |
| 用户身份 `userId` | 稳定账户、资源归属、沙箱 owner | 否 |
| 会话身份 `sessionId` | 对话隔离、工作区目录、回放、记忆隔离 | 是 |
| 长期记忆 owner `ltmOwner` | 主 Agent/子 Agent 的记忆可见范围 | 是 |
| 沙箱 owner `ownerKey` | E2B 复用实例的唯一归属 | 否，只能是稳定账号级身份；通常为 `user:<userId>`，ERP 登录可沿用 `erp:<id>` |

关键边界：

- 子 Agent 是独立会话，可以独立打开、独立续聊、独立执行工具。
- 子 Agent 的 conversation、run、workspace 目录可以独立。
- 子 Agent 的长期记忆继续隔离。
- 但同一真实用户派生的所有 Agent，执行型沙箱必须归到同一个 `user:<userId>`。

### 4. ownerKey 解析规则

新增统一入口：

`Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/SandboxOwnerResolver.java`

解析顺序：

1. 使用当前 `sessionId` 查询会话账本中的持久化 `user_id`。
2. 如果账本查到了真实用户，返回 `user:<userId>`，并以数据库结果为准。
3. 账本不可用或没有用户时，才允许使用当前上下文中明确存在的稳定账号身份（`USER` 或既有 `ERP`）。
4. 如果 `ltmOwner` 是 `SESSION` 类型或 `anonymous`，不允许拿它充当沙箱 owner。
5. 最终仍无法确认真实用户时返回 `null`，由 Bash/Code Execution fail-closed，明确报“缺少用户身份”。

禁止行为：

- 禁止使用 `session:<sessionId>` 作为沙箱 ownerKey。
- 禁止使用 `sub_<agentId>`、请求 ID、工具调用 ID 或临时 UUID 作为沙箱 owner。
- 禁止因为“当前上下文里没有 userId”就悄悄新建一个匿名或会话级沙箱。

### 5. 独立子会话为什么要查数据库

不能把“子 Agent 一定继承父 Agent 的 userId”当成唯一事实来源。

子 Agent 的生命周期可能是：

1. 主 Agent 派发子 Agent，生成独立 childSessionId。
2. 子 Agent 被落库为 `session_kind=subagent` 的真实会话。
3. 主 Agent 已经结束或不在内存中。
4. 用户从侧栏重新打开子会话，直接和它续聊。
5. 此时没有父 `AgentContext` 可供继承，但子会话仍必须能找到自己的真实用户。

因此数据库中的 `dialogue_session_ledger.user_id` 才是跨进程、跨请求、跨续聊的稳定真相源。

父上下文传 `userId` 仍然要做，但它的作用是：

- 新派发时第一时间保持一致；
- 降低账本尚未查到时的歧义；
- 让工具的本地上下文完整。

它不是唯一保障。真正的兜底必须是：

```text
AgentContext.sessionId
  -> SubAgentSessionPort.findSessionUserId(sessionId)
  -> session ledger.user_id
  -> user:<userId>
```

### 6. 目标实现

#### 6.1 领域端口

`Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/adapter/port/SubAgentSessionPort.java`

新增：

```java
default String findSessionUserId(String sessionId) {
    return null;
}
```

这是 domain 对“按会话读取持久化用户”的抽象，不反向依赖 app/infrastructure。

#### 6.2 会话账本适配器

`Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/adapter/port/SubAgentSessionPortAdapter.java`

实现从 Execution Ledger 查询：

```java
DialogueSession session =
        executionLedgerWriteRepository.querySessionBySessionId(sessionId);
return session == null ? null : session.getUserId();
```

账本中不存在会话或 `user_id` 为空时返回 `null`，不能猜测用户。

#### 6.3 子 Agent 上下文

`Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/subagent/SubAgentContextFactory.java`

派发时补上：

```java
.userId(parent.getUserId())
```

同时保持：

```java
.skipMemory(Boolean.TRUE)
.ltmOwner(null)
```

这两组配置分别表达：

- `userId`：资源归属与父会话一致。
- `skipMemory + ltmOwner=null`：子 Agent 不读写父 Agent 的长期记忆。

#### 6.4 工具接入

`BashTool` 和 `CodeExecutionTool` 不再各自复制一套 owner 解析，统一调用：

```java
String ownerKey = SandboxOwnerResolver.resolve(context);
if (ownerKey == null) {
    return ToolResultPayload.failureFrom("缺少用户身份，无法执行...", null);
}
```

涉及位置：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/shell/BashTool.java:120`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/CodeExecutionTool.java:104`

### 7. 回归测试

#### 7.1 主 Agent 与子 Agent owner 一致

`SandboxOwnerResolverTest.parentAndChildShouldResolveSameUserSandboxOwner`

断言：

- 子上下文 `userId = user-1`。
- 子上下文 `ltmOwner = null`，证明长期记忆仍隔离。
- 主上下文解析为 `user:user-1`。
- 子上下文解析为 `user:user-1`。

#### 7.2 独立子会话从数据库恢复用户

`SandboxOwnerResolverTest.independentChildShouldRecoverUserFromPersistedSession`

构造一个没有父上下文、没有 `userId`、但有 `sessionId` 的独立子会话，端口返回账本中的 `user-1`，最终必须解析为 `user:user-1`。

#### 7.3 无法确认用户时必须失败

`SandboxOwnerResolverTest.unresolvedUserShouldFailClosedInsteadOfUsingSessionOwner`

账本不存在该会话且没有用户时，必须返回 `null`，不能返回 `session:<id>`。

#### 7.4 适配器查询测试

`SubAgentSessionPortAdapterTest` 覆盖：

- 已存在子会话返回持久化 `userId`。
- 不存在的会话返回 `null`。

### 8. 验收标准

- 同一真实用户的主 Agent 和所有子 Agent 沙箱 ownerKey 完全相同，均为 `user:<userId>`。
- 独立打开的子会话不依赖父 Agent 内存，也能从账本恢复同一个 ownerKey。
- 子 Agent 的 `ltmOwner` 仍为空，长期记忆隔离行为不变。
- 无法确认用户时不创建新沙箱，而是明确失败。
- 后端仍按 `sessions/{sessionId}/` 使用独立工作目录，不把不同会话的文件混到一起。
- 已有历史孤儿沙箱不在本次自动清理范围内；修复后应停止继续新增，再单独审计并清理旧实例。

### 9. 已执行的定向验证

领域层：

```powershell
mvn.cmd -pl Cute-agent-domain -am test `
  "-DskipTests=false" `
  "-Dtest=SandboxOwnerResolverTest,SubAgentChildSessionPersistenceTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

结果：6 个测试通过。

应用/端口层：

```powershell
mvn.cmd -pl Cute-agent-app -am test `
  "-DskipTests=false" `
  "-Dtest=SubAgentSessionPortAdapterTest,SubAgentParentToolUseIdTest,SkipMemoryGuardTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

结果：14 个测试通过。

注意：PowerShell 下 Maven 的 `-D...` 参数必须加双引号，否则 `-Dsurefire...` 可能被 PowerShell/Maven 误解析为生命周期阶段。

### 10. 建议提交拆分

1. `test(sandbox): 增加主 Agent 与独立子 Agent owner 回归`
2. `feat(sandbox): 从会话账本恢复稳定用户归属`
3. `fix(sandbox): 统一 Bash 与 Code Execution 使用用户级 owner`
4. `test(sandbox): 验证无法识别用户时 fail-closed`
5. `docs(sandbox): 补充沙箱身份与长期记忆隔离边界`

### 11. 非目标

- 不把长期记忆改成用户共享；子 Agent 记忆隔离保持原状。
- 不取消子 Agent 的独立会话、独立 run、独立 workspace。
- 不修改 `sessionId` 的生成方式。
- 不修改 E2B 的暂停/回收策略。
- 不在本次计划中自动删除已经存在的 paused sandbox。

---

## 第二部分：代码型工具 Observation Token 收敛

以下第 0 至 13 节聚焦模型可见内容。核心目标：只压缩模型实际看到的 `llmObservation`，完整结果继续保留在账本和前端。

## 0. 一句话结论

当前普通成功结果已经经过 `ToolObservationFormatter` 压缩，问题主要集中在三类路径：

1. 浏览器截图或其他二进制内容仍可能以 base64 混在 `stdout` 中进入模型。
2. `ok=false`、`failed=true`、敏感命令和部分浏览器失败路径会绕过投影器，把整段 JSON 回传给模型。
3. 文件下载 URL、预览 URL、文件大小、耗时、内部同步状态等元数据，对 UI 有价值，但对大多数模型决策没有价值。

本次改造只调整“模型观察通道”，不改变工具入参、不改变 `tool_result` 完整结果、不改变前端卡片、不改变 SSE 协议。

## 1. 当前通道与事实

### 1.1 两条结果通道

代码已经区分两类内容：

| 字段 | 用途 | 是否给模型 |
| --- | --- | --- |
| `llmObservation` | 主智能体下一轮看到的紧凑 observation | 是 |
| `toolResult` | 完整结果，供账本、历史回放、前端卡片和排障使用 | 否 |
| `llmData` | 工具返回的结构化原始数据 | 间接使用 |

本次改造的硬边界：

- 不删除 `llmData` 中的字段。
- 不删除 `toolResult` 中的字段。
- 只改变 `ToolObservationFormatter`、`ToolExecutionPipeline` 和相关工具生成 observation 的方式。

### 1.2 当前中央处理流程

关键入口是 `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/ToolExecutionPipeline.java:535`。

当前行为：

```text
工具返回 ToolResultPayload
  -> 如果 llmObservation 已预填，直接使用
  -> 否则如果 failed=true，跳过 ToolObservationFormatter
  -> 否则调用 ToolObservationFormatter
  -> formatter 发现 ok=false，返回 null
  -> 回退 serializePayload，把完整 JSON 交给模型
  -> 超过预算再走 ToolObservationBudget
```

这里有三个问题：

- 预填的 `llmObservation` 不受后续 formatter 约束。
- `failed=true` 直接跳过紧凑投影。
- `ok=false` 对已注册投影器也直接放弃投影。

### 1.3 当前已有压缩

`ToolObservationFormatter.java` 已经为以下工具注册了投影器：

- `bash`
- `code_execution`
- `host_cli`
- `browser`
- `agent_browser`

普通成功路径目前大致会保留：

- `stdout`
- `stderr`
- 非零退出码
- 超时和截断提示
- 产物相对路径
- CLI 的 `error_hint` / `notice`

这是正确方向，不需要推倒重做。

### 1.4 当前主要浪费点

#### 问题 A：浏览器 base64 仍可能进入模型

`BrowserCliSupport.java:162` 会从顶层字段删除 `imageBase64`：

```java
fields.remove("imageBase64");
```

但原始 `stdout` 没有被清理。随后 `ToolObservationFormatter.projectCli()` 会把 `stdout` 原样写入模型 observation。

如果浏览器命令的 stdout 中带有：

```json
{
  "url": "https://example.com",
  "tree": "...",
  "imageBase64": "iVBORw0KGgo..."
}
```

那么模型仍会收到整段 base64。单次截图可能达到几十 KB，是最应优先消除的 token 浪费。

#### 问题 B：失败时回退完整 JSON

`ToolObservationFormatter.java:99`：

```java
if (Boolean.FALSE.equals(data.get("ok"))) {
    return null;
}
```

后果：

- Bash 非零退出时，`ok=false`，完整 JSON 可能进入模型。
- `host_cli` 失败时，`ok=false`，完整 JSON 可能进入模型。
- `agent_browser` 失败时，`ok=false`，完整 JSON 可能进入模型。

这些 JSON 通常包含：

- `tool`
- `ok`
- `duration_ms`
- `workspace_sync`
- 文件 URL
- 文件大小
- 其他内部元数据

#### 问题 C：部分路径主动预填完整 JSON

相关位置：

- `BrowserTool.java:155`
- `BrowserTool.java:165`
- `AgentBrowserTool.java:117`
- `BrowserCliSupport.java:111`

这些路径直接执行：

```java
.llmObservation(JSON.toJSONString(fields))
```

结果：

- 跳过工具专用投影器。
- 跳过 `ToolObservationBudget`，除非外层 `maxObserve` 再次截断。
- 失败、敏感命令等最需要严格控制的路径，反而发送了最多元数据。

#### 问题 D：非必要文件元数据重复出现

工具结果中常见字段：

```json
{
  "relativePath": "out/report.xlsx",
  "fileName": "report.xlsx",
  "fileSize": 28421,
  "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "ossUrl": "https://files.example/download/out/report.xlsx",
  "downloadUrl": "https://files.example/download/out/report.xlsx",
  "previewUrl": "https://files.example/preview/out/report.xlsx",
  "domainUrl": "https://files.example/preview/out/report.xlsx"
}
```

模型通常只需要：

```text
files:
- out/report.xlsx
```

其余字段应由前端和账本消费。

#### 问题 E：成功同步状态占比过高

Bash、Code Execution 和 CLI 工具可能返回：

```json
{
  "workspace_sync": {
    "backend": "e2b",
    "stateVersion": 2,
    "status": "ok",
    "uploaded": [],
    "downloaded": [],
    "skipped": [],
    "conflicts": [],
    "warnings": []
  }
}
```

成功时这整段对模型没有决策价值。只有当同步失败、存在冲突或需要模型处理警告时，才应给最小摘要。

#### 问题 F：artifact failure 信息投影不完整

`projectCli()` 对 `artifact_failures` 调用 `appendPaths()`，但失败项通常包含的是：

- `reason`
- `message`
- `fileName`

如果失败项没有相对路径，模型可能完全看不到“产物登记失败”。这不是 token 浪费，而是信息丢失，应与本次收敛一起修正。

## 2. 字段取舍总表

### 2.1 默认保留

- 命令或代码真实产生的 `stdout` / `stderr`。
- 非零 `exit_code`。
- `timed_out`。
- `truncated`。
- 可执行的错误原因。
- `error_hint` / `retry`。
- 浏览器会话重建提示。
- 截图上传失败提示。
- 产物相对路径。
- 产物登记失败的文件名和原因。
- 同步冲突、同步失败、必须处理的 warning。

### 2.2 默认丢弃

- `tool`：模型刚调用该工具，不需要再告诉它工具名。
- `ok`：成功与否由工具调用状态和 observation 文案表达。
- 成功时的 `status`。
- `duration_ms`：面向日志和 UI。
- `cwd`：工具描述已说明工作目录。
- `exit_code=0`。
- `file_size`。
- `mimeType`。
- 所有重复的下载、预览、OSS、domain URL。
- `artifactRef`。
- 成功时的 `workspace_sync` 明细。
- 同步数组中的 skipped 明细。
- base64 二进制正文。

### 2.3 条件保留

- `workspace_sync`：仅保留失败、冲突或影响后续文件读取的警告。
- `deleted_files`：只保留相对路径，且仅在确实发生删除时显示。
- `result`：`code_execution` 的返回值仅在非空、未被 stdout 覆盖且对用户任务有语义时保留。
- 产物 URL：只有下游工具契约确实需要时才保留一个规范 URL，不能同时保留多个别名。

## 3. 各工具目标协议

### 3.1 Bash

#### 保留

```text
<stdout>

stderr:
<stderr>

exit_code: 1
[timed out]
[output truncated]
files:
- output/report.csv
deleted:
- tmp/remove-me.txt
```

#### 丢弃

- `tool`
- `ok`
- `duration_ms`
- `cwd`
- `exit_code=0`
- `file_size`
- 文件 URL
- 成功 `workspace_sync`
- skills 物化/同步清单

#### 规则

- 非零退出必须可投影，不能因为 `ok=false` 回退完整 JSON。
- `deleted_files` 只列相对路径，不列同步状态对象。
- `workspace_sync.status != ok` 时输出稳定错误码和最小原因。

### 3.2 Code Execution

#### 保留

```text
<stdout>

stderr:
<stderr>

Error: ...
result: <仅在必要时>
files:
- output/chart.png
deleted:
- tmp/stale.csv
```

#### 丢弃

- `tool`
- `ok`
- 成功时的 `status`
- `file_size`
- `mimeType`
- 重复 URL 别名
- 成功 `workspace_sync`

#### 规则

- `result` 与 `stdout` 重复时只保留一份。
- `result` 过长时继续走 observation budget，不单独突破上限。
- `workspace_sync` 只投影冲突、错误和必要 warning。

### 3.3 host_cli

#### 保留

```text
<stdout>

stderr:
<stderr>

exit_code: 2
hint: ...
files:
- downloads/result.json
failed files:
- downloads/partial.pdf: capture failed
```

#### 丢弃

- `tool`
- `ok`
- `duration_ms`
- 文件 URL
- 文件大小
- MIME

#### 规则

- 成功退出不输出 `exit_code: 0`。
- `error_hint` 和 `retry` 属于可执行信息，应保留。
- `artifact_failures` 必须输出文件名和失败原因，不能只尝试读 `relativePath`。

### 3.4 browser

#### 保留

- 命令语义结果。
- 页面 `tree` 或结构化结果。
- URL。
- `error_hint` / `retry`。
- 会话重建 `notice`。
- 截图上传失败提示。
- 产物相对路径。

#### 丢弃

- `imageBase64`
- `artifactRef`
- 内部 `tool`
- `duration_ms`
- `ok`
- 文件大小、MIME、下载和预览 URL
- 已被结构化结果覆盖的重复 raw JSON

#### 截图规则

1. 截图必须先转成多模态媒体或工作区文件。
2. 模型 observation 中禁止出现 `imageBase64`。
3. `artifactRef` 只是内部引用，不应出现在模型可见文本。
4. 如果截图上传失败，保留一条可执行的 `image_upload_failed` 说明。
5. 页面语义 `tree` 和 URL 不应被误删。

### 3.5 agent_browser

规则与 `browser` 基本一致，另外必须保留：

- `notice`：会话重建后需要重新导航。
- 失败时的稳定错误码。
- `retry` / `error_hint`。

敏感命令路径也必须使用同一套紧凑投影，不允许直接预填完整 JSON。

## 4. 产物字段统一协议

### 4.1 模型可见格式

统一为路径列表：

```text
files:
- output/report.xlsx
- output/chart.png

failed files:
- output/partial.pdf: 文件登记失败
```

### 4.2 URL 处理策略

默认不向模型暴露以下字段：

- `ossUrl`
- `downloadUrl`
- `previewUrl`
- `domainUrl`
- `artifactRef`

但存在一个已确认的兼容性待决策点：

- `CodeExecutionTool.java:41` 的说明写着 `produced_files[].url` 可作为 `document_generate` 的 `image.url`。
- 当前 `ToolObservationFormatter.projectCodeExecution()` 实际只投影路径，不投影 URL。
- `CodeInterpreterTool` 还有单独的 `appendArtifactUrls()` 逻辑，会向模型追加产物 URL。

因此不能在不确认下游依赖时一律删除 URL。实施前必须二选一：

1. **兼容方案**：仅对下游确实需要 URL 的产物保留一个规范 `url`，删除其余 URL 别名和文件元数据。
2. **路径方案**：改造 `document_generate` 等下游工具，使它们接受工作区相对路径，然后从 observation 中完全移除 URL。

本次计划默认采用兼容方案，先消除重复和不必要的 URL，不破坏现有下游能力。后续是否改成路径方案，另立任务。

## 5. 目标架构

### 5.1 状态感知的投影

`ToolObservationFormatter` 从“只负责成功结果”改为“状态感知投影”：

```text
SUCCESS       -> projectXxxSuccess
SOFT_FAILURE  -> projectXxxFailure
HARD_FAILURE  -> projectXxxFailure / projectXxxError
UNREGISTERED  -> 保持现有完整 JSON 回退，避免影响其他工具
```

建议形式：

```java
ToolObservationFormatter.format(toolName, llmData, failed, ok)
```

或者：

```java
ToolObservationFormatter.format(toolName, llmData, ObservationOutcome outcome)
```

不要求所有工具立即接入 failure projector。只有本计划覆盖的五个工具启用，其他工具保持现有行为。

### 5.2 预填 observation 的约束

规则：

1. 本计划覆盖的工具不再预填完整 JSON observation。
2. 如果工具仍需自定义 `llmObservation`，该文本也必须经过统一的 observation budget。
3. 敏感命令只影响可见字段的脱敏，不改变“紧凑投影”原则。

### 5.3 预算必须统一执行

当前只有未预填的 observation 才经过 `ToolObservationBudget.apply()`。

目标：

```text
所有最终写回模型的 observation
  -> 恰好执行一次 ToolObservationBudget.apply()
```

需要保证：

- 不重复落盘。
- 不重复截断。
- 不把已经生成 `<persisted-output>` 的信封再次持久化。
- 超大 `stdout` 仍然可以在工作区保存，并给模型 2 KB 预览和可续读路径。

### 5.4 浏览器 stdout 清洗

建议增加浏览器专用 observation 构造：

```text
raw fields
  -> structured semantic result
  -> sanitized stdout without imageBase64
  -> compact observation
```

不能直接修改完整 `tool_result`。清洗只作用于模型可见副本。

推荐顺序：

1. 优先使用已解析的结构化字段：`tree`、`url`、命令结果、错误。
2. 如果必须保留 raw JSON，删除 `imageBase64` 等二进制字段。
3. JSON 解析失败时，使用有边界的正则兜底清理，并记录 debug 日志。
4. 清理后若文本过长，继续走统一 observation budget。

## 6. 实施阶段

### Phase 1：先建立回归测试

目标：用测试锁定当前浪费点和需要保留的语义。

新增或修改：

- `ToolObservationFormatterTest`
- `BrowserCliSupportTest`
- `BashToolExitStatusTest`
- `CodeExecutionToolRequestTest` 或新增观察投影测试
- CLI/Browser 工具结果测试

基础断言：

- `tool_result` 仍保留完整字段。
- `llmObservation` 不包含默认丢弃字段。
- `imageBase64` 永远不进入模型 observation。
- Bash 非零退出仍是紧凑文本。
- Code Execution 失败仍包含错误的可诊断信息。

### Phase 2：改造中央投影入口

- 为 `ToolObservationFormatter` 增加状态感知投影。
- 为五个工具增加 failure/soft-failure projector。
- `ToolExecutionPipeline` 不再因为 `failed=true` 无条件跳过 formatter。
- 未注册工具继续回退完整 JSON，控制影响面。
- 所有预填 observation 统一进入 budget。

验证：

- 现有 `ToolObservationFormatterTest` 全部通过。
- `ToolObservationSerializerTest`、`ToolLlmObservationRuntimeTest` 不回归。

### Phase 3：收敛 Bash 与 Code Execution

- Bash 非零退出走紧凑 projector。
- Code Execution 失败走紧凑 projector。
- 文件只投影相对路径。
- 成功同步状态不进入 observation。
- 同步冲突只输出错误码和最小原因。

验证：

- 非零退出仍能看到 stderr 和 exit code。
- Python 异常仍能看到 traceback / error。
- 文件产物仍能被后续工具定位。

### Phase 4：收敛 host_cli

- 成功和失败都走 `projectCli()`。
- 删除 `tool` / `ok` / `duration_ms`。
- 产物路径统一输出。
- `artifact_failures` 输出文件名和原因。

验证：

- 失败 CLI 不再把完整 JSON 交给模型。
- 下载产物仍可定位。
- 登记失败不会被静默吞掉。

### Phase 5：收敛 browser 与 agent_browser

- 删除 `BrowserTool` / `AgentBrowserTool` 中的完整 JSON 预填。
- 浏览器失败和敏感命令使用统一投影。
- observation 中清除 `imageBase64`。
- 保留 `tree`、`url`、`notice`、`error_hint`、`retry`。
- 删除 `artifactRef`、文件元数据和重复 URL。

验证：

- 截图以多模态媒体或文件形式存在，不以 base64 文本进入模型。
- 页面 tree 仍可用。
- 会话重建提示仍可触发重新导航。
- 敏感命令不泄漏 CDP URL、Cookie 或内部 relay 信息。

### Phase 6：文档和最终验收

- 更新本计划状态。
- 补充最小字段契约说明。
- 跑定向测试和受影响模块编译。
- 如条件允许，用真实会话采样一次 Bash、Code Execution、host_cli、browser 的 observation 长度差异。

## 7. 测试清单

### 7.1 Bash

- 成功命令只输出 stdout。
- 非零退出输出 stdout、stderr、exit_code。
- `duration_ms`、`cwd`、`tool`、`ok` 不出现。
- 文件 URL 和大小不出现。
- 成功 `workspace_sync` 不出现。
- `deleted_files` 只出现相对路径。

### 7.2 Code Execution

- 成功结果不出现 `status`、`ok`、文件大小和 URL 别名。
- 失败结果仍出现 error、stdout、stderr。
- `workspace_sync` 冲突时出现稳定错误码。
- `result` 与 stdout 重复时不重复显示。
- 需要的单个下游 URL 按兼容策略保留。

### 7.3 host_cli

- 成功结果不出现 `duration_ms`。
- 失败结果不回退完整 JSON。
- `error_hint` 和 `retry` 保留。
- `artifact_failures` 显示文件名和原因。

### 7.4 browser / agent_browser

- observation 不包含 `imageBase64`。
- observation 不包含 `artifactRef`。
- 页面 `tree`、URL、`notice` 保留。
- 截图上传失败提示保留。
- 失败和敏感命令不预填完整 JSON。
- 上游 stdout 中的普通业务 URL 不被误删。

### 7.5 通道隔离

- `tool_result` 仍包含完整 `llmData` 序列化。
- 前端工具卡片字段不减少。
- 历史回放不减少字段。
- SSE 协议不新增或删除字段。

## 8. 验收标准

### 8.1 功能

- 模型仍能正确判断命令成功或失败。
- 模型仍能看到错误、stdout、stderr 和可执行重试提示。
- 模型仍能找到产物文件。
- 模型仍能使用浏览器页面语义结果。
- 下游依赖单个产物 URL 的能力不被破坏。

### 8.2 Token

- 模型 observation 永远不包含 base64 图片正文。
- 普通成功结果不再出现内部元数据。
- 失败结果不再因为 `ok=false` 自动膨胀成完整 JSON。
- 成功同步明细不进入模型。
- 文件列表每个产物最多保留一个规范路径，兼容例外下最多再加一个 URL。

### 8.3 稳定性

- 未接入本次改造的工具行为不变。
- 所有 observation 只执行一次预算处理。
- formatter 异常时仍安全回退，不让工具执行失败。
- 敏感输入脱敏不因紧凑投影而被绕过。

## 9. 风险与回退

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 误删下游需要的 URL | `document_generate` 等无法使用图片 | 先用兼容例外保留一个规范 URL，并补契约测试 |
| 误删浏览器语义 tree | 模型失去页面上下文 | 删除 raw 重复 JSON，不删除结构化 `tree` |
| 失败投影信息不足 | 模型无法诊断错误 | failure projector 必须保留 error、stdout、stderr、exit code、hint |
| 预填 observation 重复做 budget | 多次落盘或错误信封 | 中央入口只处理一次，并加幂等测试 |
| 清洗 raw stdout 误删业务内容 | 命令结果缺失 | 只删除已知二进制键；无法识别时保留原文并依赖预算 |
| 改动影响其他工具 | MCP/legacy 观察回归 | failure projector 只对白名单工具启用 |

回退方式：

- 如需快速回退，可关闭五个工具的状态感知 projector，回退到当前成功投影和完整 JSON 回退。
- `tool_result` 和前端字段始终未改，回放数据不需要迁移。

## 10. 非目标

- 不修改 `bash`、`code_execution`、`host_cli`、`browser`、`agent_browser` 的参数 schema。
- 不修改工具调用 UI。
- 不修改 SSE、TimelineItem 或 Execution Ledger。
- 不压缩 `tool_result`。
- 不重构所有 MCP 工具的 observation。
- 不重新设计 `document_generate` 的入参协议。
- 不放宽敏感命令的脱敏规则。
- 不改变现有 30 KB / 20 KB observation budget。

## 11. 建议提交拆分

1. `test(tool-observation): 增加代码型工具观察裁剪回归`
2. `refactor(tool-observation): 支持成功与失败状态投影`
3. `fix(tool-observation): 收敛 Bash 与 Code Execution 观察`
4. `fix(tool-observation): 收敛 CLI 与浏览器观察`
5. `fix(browser): 移除模型观察中的截图 base64`
6. `docs(tool-observation): 补充模型可见字段契约`

每个提交都必须满足：

- 修改代码行直接对应本计划中的字段规则。
- 不混入无关重构。
- 定向测试通过。
- 涉及模块编译通过。

## 12. 实施前待确认

1. `CodeExecutionTool` 的 `produced_files[].url` 是否仍在真实任务中被 `document_generate` 使用。
2. `CodeInterpreterTool.appendArtifactUrls()` 是否必须与 `code_execution` 使用相同 URL 策略。
3. 浏览器 `tree` 与 raw JSON 的保留优先级是否需要按 `observe/snapshot/evaluate` 命令细分。
4. 失败 observation 是否需要单设低于 30 KB 的专用上限；本计划暂不新增上限，先消除结构性冗余。
5. 敏感命令的 stdout 是否允许进入模型；当前代码允许，只是脱敏 CDP URL。若不允许，需要另立安全策略任务。

## 13. 成功判据

本计划完成后，应达到以下结果：

```text
普通成功：
  只看到 stdout/stderr、必要状态和产物路径

非零退出或失败：
  只看到 stdout/stderr、exit code、error、hint 和必要产物路径

浏览器截图：
  模型看到图片媒体或工作区路径，不看到 base64 和 artifactRef

文件产物：
  模型看到相对路径；兼容例外下最多看到必要的一个 URL

内部状态：
  duration、同步明细、文件大小、MIME、重复 URL 只留在 tool_result
```

这份边界稳定后，再考虑把同样的方法扩展到其他结构化工具，而不是一次性改动全部 MCP 结果。
