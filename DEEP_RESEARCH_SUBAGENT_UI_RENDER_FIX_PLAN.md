# Deep Research 与子 Agent 预览渲染修复计划

> 状态：已完成（含第二轮收尾）
> 日期：2026-11-19
> 适用项目：`D:\Java Code\ai-agent\Cute-agent`
> 范围：主 Agent Deep Research 运行态预览、主 Agent 深度思考闪烁、子 Agent 独立会话空态、子 Agent Deep Research 卡片闪烁

## 1. 目标

修复以下五个用户可见问题：

1. 主 Agent 的 Deep Research 在运行中不显示过程卡，工具结束后才突然出现。
2. 主 Agent 的深度思考在多个规划轮次之间反复收起、展开。
3. 子 Agent 正在运行且已有轨迹时，预览面板偶尔显示“该子 Agent 没有可用的独立会话轨迹”。
4. 子 Agent 的 Deep Research 从查询分解进入搜索、章节总结时整块屏闪。
5. Deep Research 生成的 Markdown 报告使用独立且不统一的排版，正文观感与项目终答不一致。

成功标准：

- Deep Research 的 `extend`、`search`、`chapter_summary` 阶段都能触发主时间线刷新。
- 同一搜索结果进入章节总结后保持同一张 React 卡片身份，不因阶段切换重新挂载。
- 主 Agent 在整轮仍运行时，规划思考块不会因为短暂 `streaming=false` 自动开合。
- 子 Agent 启动帧携带子 run requestId，详情面板可在历史可见前直接订阅实时 run。
- 子 Agent 正在启动且尚无 `childSessionId` 时显示“正在建立子会话”，不显示无轨迹空态。
- Markdown 报告预览复用项目统一的 `MarkdownRenderer` 与聊天阅读样式，不再维护报告专属排版。

## 2. 非目标

- 不重做 Deep Research 后端协议或工具输出结构。
- 不改变 Execution Ledger、canonical timeline 或现有 SSE 传输方式。
- 不引入新的全局动画框架，也不通过简单关闭全部动画来掩盖数据刷新问题。
- 不重构无关的 OpenUI、聊天输入、会话侧栏或后台任务逻辑。

## 3. 代码级原因

### 3.1 主 Agent Deep Research 延迟出现

`ui/src/utils/chat/renderTasks.ts` 的 `buildTaskFactsSignature()` 没有覆盖 Deep Research 的阶段字段：

- `resultMap.messageType`
- `resultMap.isFinal`
- `searchResult.query`
- `searchResult.docs`
- `chapters`
- `chapterStreaming`
- `chapterSummary`
- `answer`

`ui/src/utils/chat.ts` 的 `buildConversationTaskData()` 按该签名缓存派生任务树。主 Agent 的 `multiAgent` 对象原地更新，签名不变时会继续返回旧任务树；直到最终 `isFinal` 或答案变化才刷新。

### 3.2 主 Agent 深度思考闪烁

`ThinkingBlock` 在 `streaming=false` 后经过 220ms 自动收起。主 Agent 的 PlanSolve 在多个 planner round 之间可能出现短暂的完成状态，随后新一轮又进入流式，视觉上表现为整块反复开合。

### 3.3 子 Agent 空轨迹

`SubAgentRunner` 当前先发出父流 `subagent_start`，之后才初始化子 run 账本。父卡并发建立、父工具调用 ID 暂缺或历史查询过早时，详情面板拿不到可订阅的 `childRequestId`，只能进入空态。

`useSessionTimeline()` 已支持 `requestIdOverride`，但父流没有传子 run requestId，详情面板也没有使用该参数。

### 3.4 子 Agent Deep Research 屏闪

除了 `processDeepSearchTask()` 内部按查询 / 章节拆分卡片之外，最终 React 卡片身份
还会继承 `handleTaskData()` 传入的 `baseId`。该 `baseId` 原先直接由
`task.messageTime + taskIndex` 生成，而实时打印器 `AgentSessionPrinter` 每发送一帧
都会写入新的 `System.currentTimeMillis()`：

- 同一 Deep Research 工具调用会连续发送 `extend / search / chapter_summary / report`
  多帧；
- 每一帧的 `messageTime` 都可能不同；
- `baseId` 因此每帧变化，`processTaskForRender()` 生成的 `id` 也随之变化；
- React 把同一张逻辑卡当成新节点，卸载后重新挂载，`DeepSearchPreviewItem` 的
  入场动画反复播放，表现为持续屏闪。

该问题与主 / 子 Agent 共用同一条任务派生和卡片渲染链，因此两端都会出现。

后续又确认了第二层闪烁根因：章节总结是逐章到达的，旧实现一旦看到
`chapters` 就只渲染“当前已经到达的章节”，尚未总结的查询卡会短暂消失，
下一帧再被补回。即使卡片 ID 稳定，React 仍会因为节点被删掉再插入而重播
入场动画；同时章节卡排在查询卡前，逐章到达时首卡会不断变化，导致整个
步骤组卸载重挂。

### 3.5 Markdown 报告排版不统一

Deep Research 的章节总结和工作区报告本身已经是 Markdown，文件预览也已经复用
`MarkdownRenderer`，但外层缺少聊天阅读区使用的 `chat-dialogue` /
`chat-markdown` / `conclusion-markdown` 样式入口，导致标题、段落、表格和行宽
没有沿用项目统一排版。

## 4. 实施步骤

### 步骤 1：补全 Deep Research 任务事实签名

文件：

- `ui/src/utils/chat/renderTasks.ts`
- `ui/src/utils/chat/taskFactsSignature.test.ts`

改动：

- 在任务事实签名中加入 Deep Research 的阶段、查询、来源数量、章节状态、章节总结和答案摘要。
- 对大文本使用现有内容哈希，避免签名长度随 payload 线性增长。
- 增加回归测试，覆盖 `extend -> search -> chapter_summary -> report` 的签名变化。

验证：

- `pnpm exec vitest run src/utils/chat/taskFactsSignature.test.ts`

### 步骤 2：稳定 Deep Research 卡片身份

文件：

- `ui/src/utils/chat.ts`
- `ui/src/utils/chat.test.ts`
- `ui/src/utils/chat/renderTasks.ts`
- `ui/src/utils/chat/renderTasks.test.ts`

改动：

- `handleTaskData()` 对 `deep_search` 不再使用实时 `messageTime` 生成 `baseId`；
  研究阶段固定复用 `messageId + research`，最终报告固定使用
  `messageId + report`。
- 搜索查询卡与章节总结卡共用同一稳定业务身份。
- 有查询时优先使用查询词生成身份；无查询时才退到章节 ID、标题或索引。
- 不再使用 `query:` / `chapter:` 两套命名空间。

验证：

- 同一 Deep Research 卡片在 `messageTime` 变化时 `id` 与步骤组 `id` 均保持不变。
- 从搜索阶段进入章节总结阶段时，同一查询对应的卡片 ID 不变。
- 章节顺序变化仍不改变已有卡片身份。

### 步骤 3：消除主 Agent 思考块自动开合

文件：

- `ui/src/components/Dialogue/ThinkingBlock.tsx`
- `ui/src/components/Dialogue/AgentStepTimeline.tsx`
- `ui/src/components/Dialogue/ThinkingBlock.test.tsx`

改动：

- 为 `ThinkingBlock` 增加“是否允许空闲后自动收起”的控制。
- 整轮 Agent 仍在运行时，顶层规划思考块保持当前展开状态；整轮结束后再按现有规则收起。
- 用户手动折叠后仍保持用户选择优先。

验证：

- `pnpm exec vitest run src/components/Dialogue/ThinkingBlock.test.tsx`

### 步骤 4：子 Agent 父帧携带 childRequestId

文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/subagent/SubAgentRunner.java`

改动：

- `subagent_start` / `subagent_end` 增加 `childRequestId`。
- 子 run 账本初始化提前到 `subagent_start` 广播之前，保证启动帧出现时 run 已可查询。

验证：

- Domain 编译通过。
- 现有子 Agent 生命周期测试通过。

### 步骤 5：详情面板直接订阅子 run 并修正空态

文件：

- `ui/src/utils/chat/subagent.ts`
- `ui/src/utils/chat.ts`
- `ui/src/utils/chat/renderTasks.ts`
- `ui/src/components/Dialogue/AgentDetailPanel.tsx`
- `ui/src/components/Dialogue/AgentDetailPanel.test.tsx`
- `ui/src/utils/chat/subAgentLifecycle.test.ts`

改动：

- 解析并保存父帧中的 `childRequestId`。
- `useSessionTimeline(childSessionId, enabled, childRequestId)` 直接订阅当前子 run。
- 子 Agent 仍在启动、但父卡暂未拿到 `childSessionId` 时显示“正在建立子会话”，不显示无轨迹空态。
- 子 Agent 已结束且确实没有会话信息时才显示空态。

验证：

- `subagent_start` 落地 `childSessionId` 和 `childRequestId`。
- 详情面板启动态与真正空态文案可区分。

### 步骤 7：统一 Markdown 报告展示

文件：

- `ui/src/components/ActionPanel/ActionPanel.tsx`
- `ui/src/components/ActionPanel/panelResolver.ts`
- `ui/src/components/ActionPanel/fileContentRender.test.ts`
- `ui/src/components/ActionPanel/MarkdownFileRenderer.tsx`
- `ui/src/components/ActionPanel/MarkdownFileRenderer.test.tsx`

改动：

- 文件型 Markdown 报告继续直接复用 `MarkdownRenderer`，不新增报告专用渲染器。
- Deep Research 工作区最终报告也通过 `ActionPanel` 的同一 `MarkdownRenderer` 分支
  使用聊天正文样式。
- 外层补 `chat-dialogue`，正文补 `chat-markdown conclusion-markdown`，与聊天终答
  共用同一套标题、段落、列表、表格、代码块和图片排版。
- 增加回归测试，确认报告实际经过统一 Markdown 展示入口并输出正文。

验证：

- `pnpm exec vitest run src/components/ActionPanel/MarkdownFileRenderer.test.tsx`

### 步骤 8：完整回归

前端：

```powershell
cd ui
pnpm exec vitest run src/utils/chat/taskFactsSignature.test.ts src/utils/chat/renderTasks.test.ts src/utils/chat/subAgentLifecycle.test.ts src/components/Dialogue/AgentDetailPanel.test.tsx src/components/Dialogue/ThinkingBlock.test.tsx src/components/Dialogue/AgentStepTimeline.test.tsx src/components/Dialogue/SubAgentDirectoryPanel.test.tsx src/components/ActionPanel/fileContentRender.test.ts src/components/ActionPanel/MarkdownFileRenderer.test.tsx
pnpm exec tsc -b
```

后端：

```powershell
mvn -pl Cute-agent-domain -am test -DskipTests=false
```

已知基线失败不作为本次回归新增问题：

- `ui/src/pages/WorkspaceMRag/utils.test.ts`
- `ui/src/components/GeneralInput/index.test.tsx`
- 并行负载下 `useSubAgentSession.test.ts` 的既有 flaky

## 5. 风险与约束

- 当前工作区存在用户未提交修改，尤其是 `SubAgentRunner.java`、`BaseAgent.java`、`ChatView/index.tsx`；只做本计划必需的最小改动，不覆盖其他修改。
- `buildTaskFactsSignature()` 每帧都会执行，新增字段必须使用长度、数量或哈希摘要，不能直接拼完整 Deep Research 正文。
- 卡片身份调整只影响前端派生展示 ID，不改变后端 run、toolCall 或 ledger 主键。
- 子 run 初始化提前后，若要发出 `subagent_start`，必须确保失败路径仍能进入统一 `finally` 关闭投影流。

## 6. 实施结果

- Deep Research 的阶段、检索来源、章节总结和答案摘要已进入任务事实签名；任务树与卡片渲染缓存都会随阶段变化刷新。
- 搜索阶段的 query 卡与章节总结卡复用同一业务身份，避免阶段切换重新挂载并重播入场动画。
- 主 Agent 整轮运行期间，顶层思考块不再因短暂 `streaming=false` 自动收起；用户手动折叠仍优先。
- 子 run 账本初始化已提前到 `subagent_start` 广播之前，父流 `subagent_start` / `subagent_end` 均携带 `childRequestId`。
- 详情面板及子 Agent 名册都保留并传递 `childRequestId`，直接订阅本轮子 run；历史与实时切换期间显示“正在读取子会话轨迹”，不再过早进入无轨迹空态。
- Markdown 报告文件预览复用项目统一的 `MarkdownRenderer`，并接入聊天阅读区排版样式。
- 实时 Deep Research 的卡片 `baseId` 已与逐帧变化的 `messageTime` 解耦；
  `extend / search / chapter_summary` 共用研究卡身份，`report` 使用稳定的报告卡身份。
- Deep Research 展示顺序改为以原始 `searchResult.query` 为唯一顺序；章节到达时
  只把对应查询卡原地升级为章节卡，未到达章节继续保留查询卡，不再删掉再插回。
- 增量 `search` 帧按查询词合并 `query/docs`，而不是整段覆盖，避免后到章节
  把先到章节的来源冲掉。
- 实时 `fileInfo` 与历史 artifact 投影统一过滤 `internal` 中间文件；
  `_search_result.txt` 不再排到最终 Markdown 报告前面。
- 历史回放的 report 阶段同时写入 `resultMap.fileInfo` 与 artifactRefs；
  运行中的 Deep Research 刷新后可由 controller 直接回放出工具卡。
- 点击工具卡和自动跟随统一通过 `resolveTaskResultMap()` 读取嵌套阶段；
  report 阶段只要存在主文件，就打开同一套 Markdown 文件预览。
- PlanSolve 的执行组 key 改为首个真实步骤的消息身份，不再随 plan 容器
  id / messageTime 变化而整组重挂；目录先到的 Deep Research 卡也提前绑定
  原始 query 身份。
- 检索来源卡复用全局入场去重，已展示过的来源在重挂时不再重复淡入。
- 子 Agent 运行中即使历史请求短暂 404，也只显示“正在读取子会话轨迹”，
  不会误报“没有可用的独立会话轨迹”。
- 旧 Deep Research 专属 Markdown CSS 分支和报告专用渲染入口已删除；
  `markdownClassName` / `deepSearchReport` / `findChapterStateForQuery`
  在生产前端与 Java 主链路均无引用。

验证结果：

- 前端改动区域 `9` 个测试文件、`74` 个测试全部通过。
- 本轮身份、顺序与章节合并修复专项 `7` 个测试文件、`183` 个测试全部通过。
- 最终回归：`11` 个前端测试文件、`142` 个测试通过；`pnpm exec tsc -b` 通过。
- Java 专项：Domain `7` 个测试、App 运行态回放 `2` 个测试通过。
- `mvn -pl Cute-agent-app -am clean compile -DskipTests` 全链路 BUILD SUCCESS。
- `git diff --check` 通过。
- `pnpm exec tsc -b` 通过。
- 子 Agent 流隔离专项 Domain 测试 `5/5` 通过。
- `git diff --check` 通过。
- 本次未重跑 Domain 全量测试；计划中的后端验证命令保留，便于提交前执行完整回归。

## 7. 第二轮补充修复

用户复现说明第一轮仍有未覆盖问题。本轮只处理以下可见缺陷，不重做 Deep Research 协议：

1. 最终报告卡不再使用专属 Markdown 样式分支；点击后直接打开右侧 Markdown 文件 tab。
2. 实时最终报告帧必须携带 artifact 文件引用，避免只有历史刷新后才有文件。
3. 调用中刷新页面后，RUNNING 的 Deep Research 工具卡不能消失。
4. Deep Research 调用途中持续闪烁，优先修复不稳定 loading 导致的重复入场动画。
5. 子 Agent 预览的空轨迹竞态继续按“已启动但历史索引尚未就绪”处理，不能过早显示空态。
6. Java 启动错误先用 clean compile 验证构造器是否仍不一致，不能只清 target 后宣称修复。

### 7.1 实施顺序

1. 共享 artifact fileInfo 映射，Deep Search 最终帧写入 `resultMap.fileInfo`。
2. `changeTask` 与自动跟随对 `deep_search/report` 优先打开主文件。
3. 删除 `panelResolver` 的 Deep Research 专属 `markdownClassName`。
4. 历史回放把 RUNNING 工具事实补进 timeline，刷新不丢卡。
5. `AgentStepTimeline` 的步骤入场动画按稳定 step id 去重；思考块自动收起改用整轮 live 状态。
6. 增加回归测试并执行前端测试、TypeScript、Domain 测试和 clean compile。

### 7.2 验证标准

- 实时最终报告帧有 `fileInfo`，前端 `getPrimaryTaskFile` 能解析出 `.md`。
- 点击最终报告卡打开文件 tab，而不是任务 tab。
- `deep_search/report + primaryFile` 自动跟随文件；过程阶段仍跟随任务。
- 运行中刷新后，历史时间线仍存在 RUNNING 的 Deep Research 工具卡。
- 同一 step id 因父级重渲染时不再重复播放入场动画。
- `ConversationHistoryReplayService` 构造器与 `ReplayProjectorAutoConfiguration` 在 clean compile 后一致。
