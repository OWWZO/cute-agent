# Apple 风前端改造 · 剩余待办计划（P1 / P2 / 观感取舍）

> 硬约束：配色零改动（除非提出更优设计并获批）；精准修改、最小闭环、只清理自己造成的混乱。
> 推进模式：逐步获批——每项开工前需用户回复「可以」。
> 验证链：`tsc --noEmit && pnpm check:tokens && npx eslint <改动文件> && npx vitest run`；超基线即回退。
> 测试基线：1459 通过 / 2 失败（129 文件 / 1461 用例）；当前 1470 通过 / 2 失败（131 文件 / 1472 用例），失败数持平即放行。
> 既有 2 失败明细：`src/pages/WorkspaceMRag/utils.test.ts:24`（toolBaseUrl 环境期望）、`src/components/GeneralInput/index.test.tsx:83`（基线既有，已归因非迁移引入）。

## 状态总览

- P0 工具卡片动效迁移：验证链 4 环全过，已放行（eslint 仅剩 DesktopControlCard :116/:121 两个既有 react-refresh warning）。
- 本计划覆盖：P1 一项、P2 一项、观感取舍三项（待用户定夺）。
- 不在本计划内：Goal 验收（Playwright 视觉基线、axe-core、18 界面亮暗清单、第三方冷评审；用户未要求，未启动）；DESIGN.md §10 Known Gaps（深色卡片 token、表格密集排版、图表配色豁免）。

## P1 · ToolArgStreamCard 开合状态收口（已批准，可开工）

- 目标：将 ToolArgStreamCard 本地 useState 开合改为 `useToolCardOpen(key, defaultOpen)`，key 取流 id。
- 步骤：
  1. 编辑前读磁盘最新 ToolArgStreamCard 与 useToolCardOpen 签名。
  2. 最小替换：删本地 useState，接入 hook；不动 props、样式与文案。
  3. 跑完整验证链 4 环。
- 验收：4 环全过；vitest 失败数不超基线 2；卡片开合行为与迁移前一致。
- 回退触发：验证链任一环节出现非既有负债失败，或 vitest 失败数大于 2。

## P2 · StatusDot 完成态 settle 与「执行失败」淡入（需先亮参数方案，用户确认后开工）

- 目标 A：StatusDot 完成态 settle——pulse 约 200ms 衰减，或微缩放回弹；仅动透明度与缩放，不动配色与布局。
- 目标 B：「执行失败」状态词淡入约 150ms，复用 `DURATION.press`（0.15）。
- 约束：仅透明度与缩放；遵守 motion-reduce 降级；不引入 useMotionConfig（ChatView 既有教训：破坏 layout.test.tsx mock）。
- 步骤：
  1. 先向用户亮参数方案（时长、缓动、衰减曲线二选一），获「可以」后开工。
  2. 读磁盘最新 StatusDot 与 motion.ts 预设。
  3. 实现后跑验证链 4 环。
- 验收：4 环全过；失败数不超基线；reduced-motion 下无动效。

## 观感取舍 · 三项（等用户定夺，未定前不动工）

1. `ToolDetail.tsx:63` danger 徽章胶囊化。
2. 大卡片圆角 22/24/28px 归并。
3. 任意圆角归并：`rounded-[10px]` 七处、`rounded-[28px]` 五处、`rounded-[18px]` 两处。

- 处理流程：逐项给出取舍建议，用户定夺后，获批项各自走最小修改加验证链。
- 说明：圆角归并属 DESIGN.md token 体系对齐，不涉及配色。

## 通用守则

- 编辑前必读磁盘最新；写后回显；磁盘变动视为现状。
- 外部未提交改动不回退（shikiTokens.ts、KimiCodeFence、EditInlineDiffContent、GeneralInput 三文件等）。
- eslint 既有负债不修：ThinkingToggle 4、DesktopControlCard 2、ActionPanel 1、WorkspaceSop 11、DataChat kpiList、button.tsx react-refresh、ChatView exhaustive-deps。
- 结构面必须实色，毛玻璃只给真浮层（DESIGN.md §4.2）。
