# 重复渲染与投影链路收口计划

> 状态：已完成
> 日期：2026-11-19
> 范围：前端任务复盘、历史 run 投影、文件类型分派；Java 子 Agent 终答、ContextUsage、run-final、DeepSearch 阶段协议

## 目标

- 让同类事实只保留一个生产入口，避免实时与历史、列表与详情、聊天与复盘各自解释。
- 修复子 Agent 终答可能越过当前轮 USER 边界的问题。
- 不改变现有 SSE 传输方式、Execution Ledger 主键和用户可见主流程。

## 实施顺序

1. 子 Agent 成功收口复用 `ReactFinalAnswerResolver`，补当前轮边界测试。
2. `ContextUsage` 统一由 `ExecutionLedgerQueryService` 提供，明确 measured / estimate 策略。
3. run-final 事件统一由共享 assembler 生成，三个回放入口共同使用。
4. DeepSearch 实时发送与历史回放共同使用 `DeepSearchStage` 到响应/结果 Map 的转换器。
5. 前端复盘报告和工具结果复用既有 Markdown、工具展示组件。
6. 历史会话 run 投影收口到 canonical `sessionTimelineChat`。
7. 文件类型判定和预览分派统一到 `resolveFilePreviewKind` / `FilePreviewSwitch`。
8. 删除已确认无生产调用的 Deep Research 遗留 helper 和方法。

## 验证

- 前端：相关 `vitest` 用例、`pnpm exec tsc -b`、`git diff --check`
- 后端：相关 Domain/Trigger 测试、Maven 编译、`git diff --check`
- 所有改动保留工作区已有未提交修改，不提交、不推送。

## 实施结果

- 子 Agent 成功收口复用 `ReactFinalAnswerResolver`，并补齐取消启动前仍写入 STOPPED 子 run 的处理。
- ContextUsage 统一由 Execution Ledger 查询服务输出，实测优先、估算兜底。
- run-final 统一由 `RunFinalEventAssembler` 生成，结论消息 ID、完成态和 artifactKeys 三处一致。
- DeepSearch `DeepSearchStage` 统一承载阶段状态，实时响应与历史 projector 共用 `DeepSearchStageEventMapper`。
- 前端复盘报告复用 `MarkdownRenderer`，复盘工具参数/结果复用工具展示组件。
- 历史 run 投影收敛到 `buildSessionTimelineChat`，文件类型判定和分派收敛到统一 kind/`FilePreviewSwitch`。
- 删除 Deep Research 的 `findChapterStateForQuery`、未消费的章节 content 字段，以及后端两个无生产调用的输出方法。
- 图片产物统一走 `ToolFileRefMapper.fromWorkspaceImageFiles`，两个 trigger 入口共用 replay VO mapper。
- Deep Research 实时 `fileInfo` 只保留可见 artifact；内部检索文件不再参与主文件选择。
- 运行中的 Deep Research 已补 controller 级回放测试，刷新后首帧仍能恢复工具卡。
- 点击报告卡与自动跟随统一按嵌套 `resultMap` 识别 report，并打开同一 Markdown 文件预览。
- 前端执行组、Deep Research 目录卡和检索来源入场身份已稳定，避免更新时整组重挂。
- 子 Agent 运行中的瞬时历史 404 不再被解释为空轨迹。

验证结果：

- 前端改动区域 `14` 个测试文件、`127` 个测试通过。
- `pnpm exec tsc -b` 通过。
- Domain 专项 `29` 个测试通过；Domain 全量排除既有无关失败后 `1161` 个测试通过。
- Trigger replay mapper `3` 个测试通过。
- `git diff --check` 通过。
- 全量 Domain 中既有的 `WorkingMemoryCompactionEventTest` 仍失败，和本次重复链路改动无关。
