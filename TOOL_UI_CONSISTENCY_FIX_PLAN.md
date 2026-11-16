# 工具 UI 苹果风一致性修复计划

> 状态：已实施，自动验证通过，待浏览器视觉验收
> 日期：2026-11-14
> 适用项目：`D:\Java Code\ai-agent\Cute-agent`
> 主要范围：`ui/src/components/Dialogue/**` 与 `ui/src/global.css`
> 目标：统一所有工具卡的摘要行、运行态、成功态、失败态和展开内容，消除 Kimi 旧卡片与当前苹果风混用的问题

## 0. 一句话结论

不再逐个工具“补样式”，而是先建立一层统一的工具详情面规范，再让 Generic、Bash、MCP、AskUser、Desktop 等渲染器接入。`ToolRow` 摘要行继续作为唯一头部，展开内容统一使用中性、轻玻璃、柔和圆角的苹果风表面；失败内容继续使用现有统一红色失败卡。

## 1. 已完成的基础修复

以下内容已经落地，作为本次计划的基线，不重复实施：

- `ToolArgStreamCard` 已改为 14px 圆角、轻玻璃底、中性图标底板和胶囊参数计数。
- 所有工具失败后统一显示 `ToolFailureCard`，不再切回 Parameters / Result。
- 没有入参、输出、计数或可打开内容的已完成 Generic 工具不再渲染死行。
- 工作区收起后重新展开会恢复已打开标签，不会再出现空白面板。

相关文件：

- `ui/src/components/Dialogue/tools/ToolArgStreamCard.tsx`
- `ui/src/components/Dialogue/tools/ToolFailureCard.tsx`
- `ui/src/components/Dialogue/tools/GenericToolCall.tsx`
- `ui/src/components/ActionView/workspaceTabStateCache.ts`

## 2. 当前检查结果

| 优先级 | 组件 | 问题 | 处理方向 |
| --- | --- | --- | --- |
| 高 | `DesktopControlCard` | 仍使用旧 Kimi 问答卡、Emoji 图标、蓝色标题底和 8px 小圆角 | 改用中性苹果风交互卡和 Lucide 图标 |
| 高 | `AskUserQuestionCard` | 回答前是旧 Kimi 问答卡，回答后是新的 `ToolRow`，同一工具前后像两个产品 | 保留交互结构，但让问答卡与工具卡共用视觉语言 |
| 中 | `ToolParametersBlock` / `ToolOutputBlock` | 使用英文大写 `Parameters / Result / Error`，与 MCP 的中文标题不一致 | 统一中文标题、字号、字重和内容面 |
| 中 | `GenericToolCall` 的 Bash 展开区 | 运行中是新的入参玻璃卡，完成后切回旧的 12px 实色描边卡 | 接入统一工具详情面 |
| 中 | `McpToolCall` | 没有接入新的入参流卡；结果卡、短结果卡、调用详情仍使用旧规格 | 增加入参流分支并统一详情面 |
| 低 | `AskUserToolCall` 原始结果 | `.kimi-au-raw` 仍是 8px 旧卡片 | 接入统一代码/原文详情面 |
| 低 | `ReadToolCall` | 入参异常且没有文件路径时可能只剩不可操作的 Read 行 | 完成态无可见内容时隐藏 |
| 低 | `McpToolCall` | 完成态无结果、无调用详情时仍可能保留死行 | 完成态无可见内容时隐藏 |

## 3. 统一设计规则

### 3.1 视觉层级

所有工具统一为两层：

1. **摘要行**：继续由 `ToolRow` 承担，保持无卡片、无底色、单行摘要。
2. **展开详情**：只有用户展开或运行中需要实时展示时才出现，统一套用工具详情面。

不要给整张工具卡再加外层边框，避免“卡里套卡”。

### 3.2 统一尺寸

- 外层面板圆角：`14px`
- 内层代码/JSON 面圆角：`10px`
- 图标底板：`24px × 24px`，圆角 `7px`
- 小按钮高度：`28px ~ 32px`
- 移动端可点击按钮不低于 `44px`

### 3.3 统一颜色

- 详情面背景：`--color-surface-raised` 与透明色混合，保持轻玻璃感
- 边框：`--color-line` 的低对比混合色
- 正文：`--color-text`
- 次级文本：`--color-text-muted`
- 代码与 JSON：继续使用现有语法色
- 红色只用于失败态，不用于普通标题或普通图标

### 3.4 统一字体

- 详情标题：中文，`12px / 500`，不使用全大写和额外字距
- 说明文字：`--font-ui`
- 命令、代码、JSON：`--font-mono`
- 参数计数：`11px / 500`，使用胶囊形

### 3.5 统一状态行为

- 运行中：显示入参流或轻量“调用中”状态，不突然换成另一套卡片。
- 成功：展开后显示结果或差异，内容面与运行中保持同一视觉语言。
- 失败：只显示统一红色失败卡，不显示参数、结果或旧错误框。
- 无可见内容的完成态：不渲染死行；有打开工作区动作的 Agent/Canvas 卡除外。

## 4. 实施阶段

### 阶段 0：建立视觉基线与回归样本

改动：

- 在 `ui/src/global.css` 增加工具详情面共用的局部变量和基础类。
- 用现有组件生成一组固定样本，覆盖 Generic、Read、Edit、Bash、MCP、AskUser、Desktop。
- 记录当前 DOM class 与关键文案，作为回归断言依据。

建议的共用类：

```css
.kimi-tool-detail {
  border: 1px solid var(--tool-detail-border);
  border-radius: var(--tool-detail-radius);
  background: var(--tool-detail-bg);
  box-shadow: var(--tool-detail-shadow);
}

.kimi-tool-detail-code {
  border: 1px solid var(--tool-detail-border);
  border-radius: var(--tool-detail-inner-radius);
  background: var(--tool-detail-code-bg);
  font-family: var(--font-mono);
}
```

验收：

- 共用变量只在工具区域生效，不改变聊天正文、输入框和工作区基础样式。
- 现有 `ToolRow` 摘要行无需改 DOM。

### 阶段 1：统一参数、结果、错误和 Bash 输出

文件：

- `ui/src/components/Dialogue/tools/ToolParametersBlock.tsx`
- `ui/src/components/Dialogue/tools/ToolOutputBlock.tsx`
- `ui/src/components/Dialogue/tools/GenericToolCall.tsx`
- `ui/src/global.css`

改动：

- 文案改为中文：`参数`、`结果`、`错误`。
- 标题统一为 12px、500 字重、正常大小写、无额外字距。
- `ToolParametersBlock` 和 `ToolOutputBlock` 的内容容器接入 `.kimi-tool-detail`。
- Bash 的 `.kimi-tool-execute` 接入统一详情面。
- 原始 JSON 兜底接入统一代码面。
- 保留现有滚动上限、复制、展开剩余行数等行为。

验收：

- 运行中的入参玻璃卡、完成后的 Bash 输出卡、普通工具的参数/结果卡使用同一圆角和边框语言。
- 不再出现英文大写标题。
- 长 JSON、长输出、空输出和超长行滚动不发散。

### 阶段 2：统一 MCP 卡片

文件：

- `ui/src/components/Dialogue/tools/McpToolCall.tsx`
- `ui/src/global.css`

改动：

- MCP 运行中接入 `ToolArgStreamCard`，与 Bash、Read、Edit 共用参数流体验。
- 结果段、短结果段、调用详情段接入统一详情面和标题规格。
- 保留“结果一级、调用详情二级”的信息层级。
- 保留复制、自动换行、空态和 pending 态。

验收：

- MCP 运行中的 UI 不早于其他工具切到旧摘要行。
- 短结果、普通文本、JSON 三种结果都不出现第二套卡片风格。
- 复制与换行按钮仍可用。

### 阶段 3：统一 AskUser 和 Desktop 交互卡

文件：

- `ui/src/components/Dialogue/AskUserQuestionCard.tsx`
- `ui/src/components/Dialogue/DesktopControlCard.tsx`
- `ui/src/global.css`

改动：

- 保留现有业务状态机、键盘操作和提交逻辑，只替换外观。
- `.kimi-qcard` 改为中性轻玻璃卡：
  - 14px 圆角
  - 中性标题区，不使用大面积蓝色底
  - 28px 图标底板
  - 32px 按钮，8px 圆角
  - 选项使用细边框，选中态使用低饱和蓝色底
- Desktop 的 Emoji 改为 Lucide `MonitorIcon`。
- AskUser 的 `?` 文本图标改为 Lucide `CircleHelpIcon`。
- AskUser 已提交后的“已回答”状态与运行中的卡片保持同一外壳。
- AskUser 的原始结果 `.kimi-au-raw` 接入统一代码详情面。

验收：

- 回答前、回答后都不再出现旧版 Kimi 视觉。
- Desktop 待操作、已完成、失败三种状态风格一致。
- 键盘数字选择、Enter 提交、Escape 收起、移动端 44px 点击区域保持。

### 阶段 4：清理剩余死行和状态跳变

文件：

- `ui/src/components/Dialogue/tools/ReadToolCall.tsx`
- `ui/src/components/Dialogue/tools/McpToolCall.tsx`
- `ui/src/components/Dialogue/tools/GenericToolCall.tsx`
- 相关测试文件

改动：

- 完成态的 Read：没有文件路径、没有摘要、没有可打开内容时不渲染。
- 完成态的 MCP：没有结果、没有调用详情、没有错误时不渲染。
- 运行态、失败态和可点击打开工作区的工具行不能被误隐藏。
- 继续保留 Agent、Canvas 等有明确动作的摘要行。

验收：

- 历史回放中不再出现只有工具名的空 Read / MCP / Bash 行。
- 运行中的真实调用仍立即出现。
- 失败工具仍显示统一失败卡。

### 阶段 5：响应式与最终视觉校对

检查宽度：

- 1391px 桌面
- 1024px 窄桌面
- 768px 平板
- 390px 手机

检查主题：

- 浅色主页
- 深色模式
- 工作区打开/收起
- 移动端工作区抽屉打开

检查状态：

- 参数流式生成
- 工具执行中
- 成功短结果
- 成功长文本
- 成功 JSON
- 执行失败
- 已停止
- 空结果
- AskUser 已回答 / 未回答
- Desktop 待操作 / 已完成

## 5. 测试计划

### 5.1 组件回归

重点覆盖：

- `ui/src/components/Dialogue/tools/toolRegistry.test.ts`
- `ui/src/components/Dialogue/tools/toolTaskAdapter.test.ts`
- `ui/src/components/Dialogue/AgentDetailPanel.test.tsx`
- AskUser 与 Desktop 相关测试

建议新增断言：

- 参数 / 结果 / 错误标题为中文。
- 展开内容带统一 `.kimi-tool-detail` 类。
- MCP 运行中渲染 `data-testid="generic-tool-arg-stream"` 或专用 MCP 入参流 test id。
- 完成态空 Read / MCP / Bash 返回空节点。
- `AskUserQuestionCard` 不再输出旧的 Emoji 图标。
- `DesktopControlCard` 使用 Lucide 图标并保留关键按钮文案。

### 5.2 命令

在 `ui/` 下执行：

```powershell
pnpm exec vitest run src/components/Dialogue
pnpm exec vitest run src/components/ActionView src/components/ChatView
pnpm exec tsc -b
pnpm build
pnpm lint
```

记录基线：

- `check-design-tokens` 当前已有 3 个 `ChannelAdmin` 违规，与本次无关。
- 若全量测试仍有既有失败，必须在报告中区分“修复引入”和“基线已有”。

## 6. 风险与控制

### 风险 1：全局 CSS 影响 PlanApproval

`.kimi-ui-card` 被问答卡和审批卡共用。优先只覆盖 `.kimi-qcard`，不要直接重写 `.kimi-ui-card` 的全部行为。

控制：

- 单独检查 PlanApproval。
- 如只需工具卡生效，使用 `.kimi-qcard` 作用域。

### 风险 2：隐藏死行误伤真实调用

如果只按文案为空判断，可能隐藏仍在运行的调用。

控制：

- 只在 `status === "ok"` 时隐藏。
- 有可打开工作区动作的卡不隐藏。
- 运行中、错误态、停止态不隐藏。

### 风险 3：AskUser 交互被视觉修改破坏

问答卡的键盘、焦点和提交状态比样式更敏感。

控制：

- 阶段 3 只改 DOM 图标和 CSS，不修改提交逻辑。
- 保留现有测试，并补键盘行为回归。

### 风险 4：MCP 入参流造成重复展示

MCP 既有参数详情，又有调用详情折叠。

控制：

- 运行中只显示入参流。
- 完成后再显示结果与调用详情。
- 不与完成态参数块同时出现。

## 7. 建议提交拆分

1. `style(ui): 建立工具详情面统一规范`
2. `style(ui): 统一参数结果与 Bash 输出卡片`
3. `style(ui): 统一 MCP 工具卡与入参流`
4. `style(ui): 统一问询与桌面控制交互卡`
5. `fix(ui): 移除空 Read MCP Bash 工具行`
6. `test(ui): 补齐工具 UI 一致性与空行回归`

每个提交只包含对应阶段，避免样式、功能和测试混在一起。

## 8. 完成标准

满足以下条件才视为完成：

- 所有工具摘要行继续使用 `ToolRow`。
- 所有展开内容共用同一套圆角、边框、底色、标题和字体规则。
- 失败态全部使用统一红色失败卡。
- AskUser 与 Desktop 不再呈现旧 Kimi 卡片视觉。
- MCP、Bash、Read、Edit 都有连续的运行态体验。
- 完成态空工具行不再出现。
- 桌面、窄屏和移动端均无溢出、挤压或按钮不可点。
- `vitest` 相关测试、`tsc -b`、`pnpm build` 通过。
- 既有无关失败在交付说明中明确列出，不混入本次结论。

## 9. 实施结果

已按阶段 1-4 完成代码修改：

- 参数、结果、错误、Bash 输出、原始 JSON 和 AskUser 原始结果统一使用中性轻玻璃详情面。
- MCP 运行态接入统一入参流卡，结果、短结果和调用详情接入同一视觉规格。
- AskUser 与 Desktop 改用 Lucide 图标，并统一为 14px 卡片、28px 图标底板、32px 按钮和移动端 44px 点击区域。
- 完成态无内容的 Read / MCP 不再渲染死行。
- Timeline 与 ActionPanel 中对应的 Desktop / AskUser 提示卡同步替换 Emoji / 文本图标。

自动验证结果：

- `pnpm exec vitest run src/components/Dialogue`：35 个测试文件，324 条通过。
- `pnpm exec vitest run src/components/ActionView src/components/ChatView src/components/ActionPanel`：32 个测试文件，262 条通过。
- `pnpm exec tsc -b`：通过。
- `pnpm build`：通过。
- 变更文件定向 ESLint：0 error，仅保留既有 Fast Refresh warning。
- 全量 `pnpm lint` 仍被仓库既有生成目录与无关文件错误阻断；`check-design-tokens` 的 3 个 `ChannelAdmin` 违规也与本次无关。

剩余验收：在 `1391px / 1024px / 768px / 390px` 下检查浅色、深色、工作区开合和各类工具状态的实际视觉效果。
