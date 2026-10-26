# Apple-grade 验收清单

> 常驻目标见 [`CLAUDE.md`](./CLAUDE.md) 顶部「当前常驻目标」。
> 状态：`待办` / `进行中` / `已验收`。每轮只推进一个界面，改完立刻更新本表。
> 四项底线：① 语义 token 零硬编码 ② 弹簧动效只来自 `lib/motion.ts` ③ 材质克制 ④ 排印与无障碍。

## Phase 0 — 一致性清账（风格升级的前置条件）

| # | 界面 | 文件 | 状态 | 备注 |
|---|------|------|------|------|
| 0.1 | SOP 工作台 | `pages/WorkspaceSop/index.tsx` | 已验收 | 50 处 slate/sky/rose/emerald 已收敛到 `--chat-*` / `--color-*` / `--status-*` token；`tsc --noEmit` 通过 |
| 0.2 | 能力库 | `pages/CapabilityLibrary/index.tsx` | 已验收 | 10 处已收敛到 `--chat-*` / `--color-*`；tsc 通过 |
| 0.3 | 绘图智能体 | `pages/WorkspaceImageGeneration/index.tsx` | 已验收 | 5 处已收敛 |
| 0.4 | OpenUI 演示页 | `pages/OpenUiDemo.tsx` | 已验收 | 6 处已收敛（仅 DEV 路由） |
| 0.5 | 工具目录 | `pages/ToolCatalog/*` | 已验收 | 5 处已收敛 |
| 0.6 | OpenUiMarkdown | `components/openui/OpenUiMarkdown.tsx` | 已验收 | 4 处已收敛 |
| 0.7 | 其余散点 | `Dialogue/AgentDetailPanel`、`ActionPanel/HTMLRenderer`、`ActionPanel/Loading`、`Dialogue/Timeline`、`WorkspaceMRag/view`、`Auth/liquid-glass/LiquidGlassAuth` | 已验收 | 各 1–2 处已收敛；全站调色板残留 0 |
| 0.8 | 裸 hex 清账 | `ConversationSidebar`（focus 边框）、`SearchListRenderer`、`DeepSearchChapterPanel` | 已验收 | UI 组件内的 `#fafafa` / `#f5f5f7` / `#1d1d1f` / `#0000004d` 已改语义 token |

## Phase 1 — 质感升级（性价比最高）

| # | 界面 | 动作 | 状态 |
|---|------|------|------|
| 1.1 | 动效基础设施 | `lib/motion.ts` 增加弹簧预设（`SPRING_SOFT` / `SPRING_SNAPPY` / `SPRING_GENTLE`）与 `useSpring` 工厂 | 已验收 |
| 1.2 | 会话侧边栏 | 高亮块 `layoutId` 改 `SPRING_SOFT`；reduced 时瞬时切换 | 已验收 |
| 1.3 | 工作区标签栏 | 已评估：tab 为即时换底色（CSS transition），高密度控件不应有弹簧延迟；ActionView 入场改用 `DURATION.message` 消除魔法数 | 已验收 |
| 1.4 | 欢迎页主视觉 | 推荐问题区（`SPRING_SOFT`）、输入区与精品区（`SPRING_GENTLE`）入场改弹簧 | 已验收 |
| 1.5 | 侧栏 + 移动端顶栏 | 改 `--chat-panel-strong` + `backdrop-blur-2xl`（`supports-backdrop-filter` 保护） | 已验收 |
| 1.6 | 排印 | 主标题 `tracking-[-0.02em]`；`RecentSessionList` 轮次计数加 `tabular-nums` | 已验收 |

## Phase 2 — 体验闭环

| # | 界面 | 动作 | 状态 |
|---|------|------|------|
| 2.1 | 按钮微交互 | `active:scale-[0.97]` 统一按压反馈：按钮组件、欢迎页推荐卡片、MRAG 工具条；停止按钮 0.92 为破坏性操作刻意强调 | 已验收 |
| 2.2 | 输入框 | focus 光晕统一为 ring-3 + accent-soft（WelcomeView / ToolCatalog / ToolTaskDialog / ReplayDisclosure）；GeneralInput 图标按钮补回焦点环（无障碍修复） | 已验收 |
| 2.3 | 全局加载 | Loading `branded` 落地 AnimatedOrb，Home 两处 Loading 已接入 | 已验收 |
| 2.4 | 欢迎页 → 对话页 | 输入框 `layoutId` 共享布局转场 | 待办 |

## 逐界面验收清单（Phase 0 全绿后逐项跑）

| 界面 | ① token | ② 动效 | ③ 材质 | ④ 排印/无障碍 | 状态 |
|-------|:--:|:--:|:--:|:--:|------|
| 欢迎页 `WelcomeView` | ☐ | ☐ | ☐ | ☐ | 待办 |
| 登录 / 注册 `Auth` | ☐ | ☐ | ☐ | ☐ | 待办 |
| 精品对话列表 / 卡片 / 详情 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 对话流 `ChatView`（multiAgent / dataAgent） | ☐ | ☐ | ☐ | ☐ | 待办 |
| 输入区 `GeneralInput` | ☐ | ☐ | ☐ | ☐ | 待办 |
| 会话侧边栏 `ConversationSidebar` | ☐ | ☐ | ☐ | ☐ | 待办 |
| 移动端抽屉遮罩 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 工作区标签栏 `ActionView` | ☐ | ☐ | ☐ | ☐ | 待办 |
| 文件列表 `FileList` / `TaskFileSidebar` / `WorkspaceFileTree` | ☐ | ☐ | ☐ | ☐ | 待办 |
| 文件预览 `FilePreview` / `FilePreviewSwitch` | ☐ | ☐ | ☐ | ☐ | 待办 |
| MRAG 工作台 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 绘图智能体 | ☐ | ☐ | ☐ | ☐ | 待办 |
| SOP 工作台 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 子 Agent 装配 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 模型接入 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 能力库 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 工具目录 | ☐ | ☐ | ☐ | ☐ | 待办 |
| 计划视图 / HITL 交互 | ☐ | ☐ | ☐ | ☐ | 待办 |

## 自动化门禁（防回潮）

| 门禁 | 命令 | 状态 |
|------|------|------|
| 颜色 + 动效零散写 | `pnpm check:tokens`（`scripts/check-design-tokens.mjs`） | 已接入：591 文件 0 违规（含 `duration-[...]` / `ease-[cubic-bezier]` / 内联 transition 校验） |
| 类型检查 | `npx tsc --noEmit` | 已通过 |
| Lint（改动文件） | `npx eslint <changed files>` | 已通过：本轮改动文件 0 error |
| 视觉基线 | Playwright 截图对比 | 待接入 |
| 无障碍 | axe-core | 待接入 |

## 完成判据

- [ ] 上表 Phase 0 全绿
- [ ] 逐界面清单在浅色 + 深色下全部勾选
- [ ] 门禁全部接入且零违规
- [ ] 未参与项目者只看操作录屏，评价「像苹果做的」
