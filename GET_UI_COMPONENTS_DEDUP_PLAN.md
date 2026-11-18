# get_ui_components 跨轮去重修复计划

> 状态：已实施
> 日期：2026-11-18
> 项目：`D:\Java Code\ai-agent\Cute-agent`
> 目标：减少 `get_ui_components` 在同一会话内重复返回 13KB 组件目录造成的 Token 浪费，同时保证压缩后和子 Agent 场景下模型仍能拿到完整签名。

---

## 1. 背景

MySQL 历史数据统计显示：

- `get_ui_components` 历史调用 24 次，涉及 20 个会话、21 个 run。
- 4 个会话重复调用过，其中 3 个是同一 run 内重复，1 个是跨 run 重复。
- 每次 observation 约 13,375～13,393 字符，约 3,300+ tokens。
- 24 次累计约 79,000 tokens 输出。

重复调用示例：

```text
session-1794659993264-60  run 2550  step 9 / step 14
session-1794625993828-8399 run 2546  step 5 / step 14
session-1792770323922-9210 run 2458  step 1 / step 4
session-1792336865656-3809 run 2390 / run 2392
```

因此问题不是“会不会浪费”，而是已经发生了。

---

## 2. 设计原则

### 2.1 不能截断目录正文

`get_ui_components` 返回的是扩展组件签名目录，模型需要完整签名才能写 openui-lang。

禁止：

- 固定截断目录正文；
- 只返回组件名；
- 只返回“请自行回忆”这类无信息 stub；
- 在模型可能已经看不到目录时仍返回 stub。

### 2.2 只压缩模型可见 observation

重复调用时：

- `toolResult` 仍保留完整目录；
- `llmData` 仍保留完整目录；
- `structuredOutput` 无；
- 仅 `llmObservation` 返回短 stub。

这样不影响：

- 前端工具卡片；
- Execution Ledger；
- 历史回放；
- SSE / TimelineItem；
- 排障。

### 2.3 状态与会话 workspace 绑定

`AgentContext` 是 run 级对象，下一轮会重建。若只放内存，跨 run 仍会重复完整目录。

因此需要轻量 sidecar 文件：

```text
<workspace>/.cute/openui-catalog-loaded.json
```

只保存版本标记，不保存目录正文。

### 2.4 压缩后必须失效

上下文压缩后，模型可能已经看不到早先返回的组件目录。此时如果继续返回 stub，会让模型缺少签名却无法重新获取。

因此压缩成功后必须：

- 清掉当前 `AgentContext` 内存标记；
- 删除工作区 sidecar 文件。

### 2.5 子 Agent 独立

子 Agent 是独立会话，不应复用主 Agent 的已加载标记。

规则：

- 子 Agent 不 hydrate 主 Agent 的 sidecar；
- 子 Agent 第一次调用仍返回完整目录；
- 子 Agent 自己的重复调用可按同一 run 内存标记去重；
- 子 Agent 不写 sidecar 文件；
- 子 Agent 压缩后只清内存。

---

## 3. 目标行为

| 场景 | 期望行为 |
| --- | --- |
| 当前轮第一次调用 | 返回完整目录，设置内存标记，写 sidecar |
| 当前轮重复调用，目录版本一致 | 返回短 stub，完整目录仍留在 `llmData` / `toolResult` |
| 下一轮调用，sidecar 存在且版本一致 | 返回短 stub |
| 目录内容版本变化 | 返回新版完整目录，更新内存标记和 sidecar |
| 上下文压缩后调用 | sidecar 已清，返回完整目录 |
| 子 Agent 第一次调用 | 返回完整目录，不读主 Agent sidecar |
| 子 Agent 重复调用 | 只按内存标记返回 stub |
| context 为空的旧测试/调用 | 保持兼容，返回完整目录 |

---

## 4. 状态设计

### 4.1 内存状态

在 `AgentContext` 增加一个轻量字段：

```java
private String loadedOpenUiCatalogVersion;
```

语义：

- `null`：当前 context 未确认加载过；
- 非空字符串：当前 context 已加载过该版本的组件目录。

不新增复杂对象，避免为一次性用途做过度抽象。

### 4.2 持久化状态

新增文件：

```text
.cute/openui-catalog-loaded.json
```

内容：

```json
{
  "version": "<sha256>",
  "loadedAt": 1760000000000
}
```

字段说明：

- `version`：组件目录内容 hash，目录中文本变化后自动失效；
- `loadedAt`：仅排障用，不参与判断。

### 4.3 版本计算

使用已有工具方法：

```java
WorkspaceReadStateStore.sha256Hex(componentsCatalog)
```

不在多处重写 hash 逻辑。

---

## 5. 实现设计

### 5.1 新增 Store

新增类：

```text
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/openui/OpenUiCatalogLoadStateStore.java
```

职责：

```text
hydrate(AgentContext)
persist(AgentContext, String version)
clear(AgentContext)
clearPersisted(String workspaceRoot)
```

边界：

- 只处理主 Agent；
- `isSubAgent(context)` 时 hydrate / persist 直接返回；
- clear 时子 Agent 只清内存，不删主 Agent sidecar；
- workspaceRoot 为空时不做落盘；
- JSON 读取失败只记 warn，不影响工具执行。

### 5.2 修改 GetUiComponentsTool

文件：

```text
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/openui/GetUiComponentsTool.java
```

 execute 流程：

```text
catalog = OpenUiResources.componentsCatalog()
version = sha256Hex(catalog)

if context == null:
    return fullPayload(catalog)

store.hydrate(context)   // 只在当前 context 未设置标记时恢复

if version.equals(context.getLoadedOpenUiCatalogVersion()):
    return stubPayload(catalog)

context.setLoadedOpenUiCatalogVersion(version)
store.persist(context, version)
return fullPayload(catalog)
```

`fullPayload(catalog)`：

```java
ToolResultPayload.builder()
    .toolResult(catalog)
    .llmData(catalog)
    .llmObservation(catalog)
    .failed(false)
    .build()
```

`stubPayload(catalog)`：

```java
ToolResultPayload.builder()
    .toolResult(catalog)
    .llmData(catalog)
    .llmObservation(STUB)
    .failed(false)
    .build()
```

stub 文案：

```text
OpenUI component catalog was already loaded earlier in this session.
Reuse the earlier catalog from tool_result; do not call get_ui_components again unless the component set changed.
```

### 5.3 修改 AgentContext

文件：

```text
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/AgentContext.java
```

新增：

```java
private String loadedOpenUiCatalogVersion;
```

新增 getter / setter，或沿用 Lombok 现有风格。

### 5.4 修改子 Agent 创建

文件：

```text
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/subagent/SubAgentContextFactory.java
```

在 child builder 明确设置：

```java
.loadedOpenUiCatalogVersion(null)
```

虽然默认就是 null，但显式写出可以防止未来误继承。

### 5.5 修改主流程 hydrate / persist

文件：

```text
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/service/execute/support/PrepareAgentContextAssembly.java
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/service/execute/support/ReactTurnCloseSupport.java
```

在现有 workspace read-state hydrate / persist 旁增加 OpenUI catalog state：

```text
hydrateOpenUiCatalogState(agentContext)
persistOpenUiCatalogState(agentContext)
```

保持 best-effort，异常只记 warn。

### 5.6 修改压缩后清理

文件：

```text
Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/BaseAgent.java
```

将现有：

```java
clearWorkspaceReadStateAfterCompaction();
```

改为统一方法：

```java
clearReloadGuardsAfterCompaction();
```

内部同时执行：

```text
WorkspaceReadStateStore.clear(context)
OpenUiCatalogLoadStateStore.clear(context)
```

为避免重复依赖，允许在异常互补降级时分别 try/catch。

---

## 6. 测试计划

### 6.1 GetUiComponentsTool 单元测试

扩展：

```text
Cute-agent-app/src/test/java/org/wwz/ai/test/domain/openui/GetUiComponentsToolTest.java
```

覆盖：

1. `context == null`：仍返回完整目录。
2. 第一次调用：完整目录，`llmObservation` 等于 catalog。
3. 同一 context 第二次调用：`llmObservation` 是 stub，`llmData` 和 `toolResult` 仍是完整目录。
4. 不同 context 模拟下一轮，先 hydrate sidecar：返回 stub。
5. 删除 sidecar 或 `clear` 后：返回完整目录。

### 6.2 OpenUiCatalogLoadStateStore 测试

新增：

```text
Cute-agent-domain/src/test/java/org/wwz/ai/domain/agent/runtime/tool/common/openui/OpenUiCatalogLoadStateStoreTest.java
```

覆盖：

- persist 后 hydrate 恢复 version；
- 空 snapshot / clear 后 sidecar 文件被删除；
- 损坏 JSON 不抛异常；
- workspaceRoot 为空时不写文件；
- 子 Agent 不 hydrate、不 persist、不删除主 Agent sidecar；
- 子 Agent clear 只清内存。

### 6.3 AgentContext / 子 Agent 测试

扩展：

```text
Cute-agent-app/src/test/java/org/wwz/ai/test/spring/ai/WorkspaceReadStateCrossTurnTest.java
```

新增断言：

- 子 Agent context 的 `loadedOpenUiCatalogVersion` 为 null。

或新增独立测试类，避免混淆职责。

### 6.4 压缩清理测试

扩展已有压缩相关测试，或新增轻量测试：

- 压缩成功后，context 的 OpenUI 标记被清空；
- sidecar 文件被删除；
- 后续调用返回完整目录。

若完整压缩链路测试过重，则至少为 `OpenUiCatalogLoadStateStore.clear(context)` 提供直接测试，并由代码调用点保证接入。

### 6.5 回归测试

运行：

```powershell
mvn -pl Cute-agent-domain "-Dtest=OpenUiCatalogLoadStateStoreTest,ToolObservationFormatterTest,ToolExecutionPipelineObservationTest" test "-DskipTests=false"
mvn -pl Cute-agent-app -am "-Dtest=GetUiComponentsToolTest,AgentToolCollectionFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test "-DskipTests=false"
```

---

## 7. 验收标准

### 7.1 功能

- 第一次调用拿到完整组件目录。
- 重复调用不把 13KB 目录再次塞给模型。
- 下一轮仍能识别已加载状态。
- 压缩后能重新拿到完整目录。
- 子 Agent 不复用主 Agent 状态。

### 7.2 通道隔离

- `toolResult` 始终是完整目录。
- `llmData` 始终是完整目录。
- 只有 `llmObservation` 在重复调用时变短。
- 不改工具 schema。
- 不改前端、SSE、TimelineItem、Execution Ledger。

### 7.3 稳定性

- sidecar 损坏不影响工具调用。
- 工作区不可写时不影响返回完整目录。
- 子 Agent 无法污染主 Agent sidecar。
- 目录内容变化后自动重新完整加载。

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| stub 后模型仍想调用 | 少省一点 token | stub 明确写“复用 tool_result” |
| 压缩后未清理 | 模型拿不到签名 | 压缩成功路径统一清 reload guards |
| sidecar 损坏 | 工具失败 | hydrate 异常只 warn，按未加载处理 |
| 目录升级 | 模型拿到旧 stub | version 使用目录内容 hash |
| 子 Agent 继承主 Agent 标记 | 子 Agent 缺少签名 | hydrate 跳过 subagent，builder 显式 null |
| 工作区不可写 | 工具失败 | persist best-effort，失败仍返回完整目录 |

---

## 9. 非目标

- 不修改组件目录内容。
- 不修改 openui prompt。
- 不截断组件签名。
- 不把组件目录写入 MySQL。
- 不清理历史会话中的旧 observation。
- 不改变 `get_ui_components` 的工具 schema。

---

## 10. 建议实施顺序

1. 增加 `AgentContext.loadedOpenUiCatalogVersion`。
2. 增加 `OpenUiCatalogLoadStateStore` 和单元测试。
3. 修改 `GetUiComponentsTool`，实现完整目录 / stub 双 observation。
4. 接入主流程 hydrate / persist。
5. 接入压缩后 clear。
6. 补子 Agent 隔离测试。
7. 跑定向测试和通道隔离测试。

完成后再按实际会话采样验证重复调用是否消失。
