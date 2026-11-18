# 工作区标签页滚动位置恢复计划

## 背景

当前工作区已经支持：

- 左侧返回上一个标签页；
- 右侧标签页列表合并“关闭其他标签 / 关闭全部标签”；
- 按实际导航顺序回退，并跳过已关闭的标签页。

但用户从子 Agent 预览面板跳到文件标签页，再返回子 Agent 标签页时，子 Agent 面板会重新挂载，滚动容器重新创建，观看位置回到顶部或错误位置。

这不是返回按钮单独的问题。只要切换标签页导致内容区卸载，所有标签页都会遇到类似问题。

## 目标

用户在一个标签页中看到任意位置，例如“第 38% 的位置”：

1. 点击其中的文件或其他入口，进入新标签页；
2. 看完后点击返回按钮或重新切回原标签页；
3. 原标签页恢复到之前的观看位置，而不是顶部或底部；
4. 如果用户之前本来就在底部，则继续沿用当前的自动跟底行为；
5. 如果用户之前展开过“显示更早的轮次”，返回后仍保持相同展开状态。

## 非目标

本计划不做以下事情：

- 不改变现有返回栈逻辑；
- 不实现“前进”按钮；
- 不把所有标签页隐藏在后台常驻；
- 不把滚动位置写入 `localStorage`；
- 不保证 PDF / iframe / 外部 Office 预览等不可控容器一定恢复成功；
- 不在本次改动中扩展新标签类型。

## 当前原因

相关代码位置：

- `src/components/ActionView/ActionView.tsx`
  - 当前只渲染选中的标签页；
  - 切换标签页会卸载旧内容并挂载新内容。
- `src/components/Dialogue/AgentDetailPanel.tsx`
  - 子 Agent 滚动容器是 `.kimi-detail-panel-body`；
  - `visibleTurnCount` 是组件内部 state；
  - 当前只有“用户本来在底部”时自动滚到底，没有恢复任意位置。
- `src/hooks/useSessionTimeline.ts`
  - 子 Agent 面板重新挂载后会重新拉取历史；
  - 内容高度在恢复瞬间可能还不稳定。

## 设计原则

### 1. 返回栈和阅读位置分开

- 返回栈回答：回到哪个标签页；
- 阅读位置回答：回到这个标签页的哪一屏。

两者不要混在一个数据结构里。

### 2. 锚点优先，比例兜底

只记录顶部或底部无法满足“回到 38% 位置”。

恢复优先级：

1. **稳定元素锚点 + 偏移**：最准确，适合子 Agent 工具调用、消息卡片、turn；
2. **连续 `scrollTop`**：内容高度没变化时最精确；
3. **比例 `scrollHeight` 占比**：内容高度变化时兜底；
4. **顶部**：只有在没有历史记录时才使用。

### 3. `atBottom` 只决定是否跟底

`atBottom` 不是观看位置。

它只用于判断：

- 用户切走时是否正在跟随最新内容；
- 返回后是否继续自动滚到底。

如果用户之前在中间查看，就不要强行滚到底。

### 4. 不保活所有标签页

不采用“所有标签页都挂载，仅用 `display: none` 隐藏”的方案。

原因：

- PDF、网页、视频、桌面预览会继续占资源；
- iframe 可能继续播放或请求网络；
- 多个 Agent 面板同时订阅实时流会增加复杂度；
- 标签页越多，性能越差。

只保存必要的阅读状态。

## 数据结构

新增一份页面内内存缓存，建议放在新模块：

`src/components/ActionView/workspaceTabScrollCache.ts`

缓存键：

```text
workspaceStateKey + tabKey
```

缓存值：

```ts
type WorkspaceTabScrollSnapshot = {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
  ratio: number;
  anchorId: string;
  anchorOffset: number;
  atBottom: boolean;
  visibleTurnCount?: number;
};
```

字段含义：

- `scrollTop`：切走时真实滚动像素；
- `scrollHeight`：当时内容总高度；
- `clientHeight`：当时视口高度；
- `ratio`：当时在可滚动区域中的位置，计算为 `scrollTop / (scrollHeight - clientHeight)`；
- `anchorId`：视口顶部附近最近一个稳定元素的 ID；
- `anchorOffset`：视口顶部距离该元素的偏移；
- `atBottom`：是否贴底；
- `visibleTurnCount`：子 Agent 面板展开了多少轮，可选。

## 稳定锚点

### 来源

优先给子 Agent 面板里的稳定块加 `data-scroll-anchor-id`：

- 工具调用卡片；
- turn 容器；
- 消息结果块。

ID 必须来自稳定业务身份，例如：

- `toolCallId`；
- `messageId`；
- `requestId`；
- `turn.key`。

不要只用数组下标，因为展开更早轮次或流式新增内容会让下标变化。

### 记录时机

在滚动事件里找到当前视口顶部附近最近的锚点：

```text
从可见区域顶部向下找第一个 data-scroll-anchor-id
记录 anchorId 和 anchorOffset
```

同时更新 `scrollTop / scrollHeight / clientHeight / ratio / atBottom`。

写入频率用 `requestAnimationFrame` 或约 100ms 节流，避免高频写入。

### 恢复时机

重新挂载后：

1. 先恢复 `visibleTurnCount`；
2. 等内容从缓存或网络加载完成；
3. 等内容高度稳定；
4. 找到 `anchorId` 对应元素；
5. 设置 `scrollTop = anchorTop + anchorOffset`；
6. 如果锚点不存在，用 `ratio * (scrollHeight - clientHeight)`；
7. 如果内容高度还没起来，延迟到下一次布局后重试；
8. 最多重试少量次数，避免无限等待。

## 子会话缓存

只恢复 `scrollTop` 可能仍会看到“刷新一下”，因为子 Agent 面板重新挂载会重新请求历史。

阶段二给 `useSessionTimeline` 增加页面内短缓存：

- 按 `childSessionId` 保存最近一次拿到的历史和时间线；
- 重新挂载时先展示缓存内容；
- 后台静默同步最新数据；
- 用户主动 reload 时绕过缓存；
- 刷新浏览器后缓存清空。

这样可以同时解决：

- 内容区先空白再出现；
- 内容高度变化导致恢复位置不准；
- 返回时有明显刷新感。

## 改动步骤

### 步骤 1：新增滚动缓存模块

文件：

- `src/components/ActionView/workspaceTabScrollCache.ts`
- `src/components/ActionView/workspaceTabScrollCache.test.ts`

内容：

- 按 `workspaceStateKey + tabKey` 读写快照；
- 支持删除单个标签页缓存；
- 支持清空当前工作区缓存；
- 支持重置全部缓存，方便测试。

验证：

- 同标签页读写成功；
- 不同标签页互不干扰；
- 关闭标签后删除成功；
- 刷新页面不持久化。

### 步骤 2：抽取滚动恢复纯函数

文件：

- `src/components/ActionView/workspaceTabScrollRestore.ts`
- `src/components/ActionView/workspaceTabScrollRestore.test.ts`

函数职责：

- 从滚动容器生成快照；
- 根据快照计算目标滚动位置；
- 查找锚点元素；
- 处理比例兜底和边界钳制。

验证：

- 内容高度不变时按 `scrollTop` 精确恢复；
- 内容高度变化且锚点存在时按锚点恢复；
- 锚点不存在时按比例恢复；
- 极端比例不会超过合法滚动范围；
- 可滚动区域为 0 时不会报错。

### 步骤 3：给子 Agent 面板接入恢复逻辑

文件：

- `src/components/Dialogue/AgentDetailPanel.tsx`

改动：

- 新增 `scrollRestoreKey` 和 `scrollScope` props；
- 给 `.kimi-detail-panel-body` 挂滚动监听；
- 滚动时更新缓存；
- `visibleTurnCount` 初始化时读取缓存；
- 用户点击“显示更早”时更新缓存；
- 挂载后等待内容稳定并恢复位置；
- `atBottom === true` 时跳过位置恢复，保持现有跟底逻辑。

验证：

- 中间位置切走再回来，恢复到同一区域；
- 底部位置切走再回来，继续跟底；
- 展开更早轮次后回来，展开状态保留；
- 流式新增内容不会把非底部用户强制拉到底。

### 步骤 4：ActionView 传入恢复身份并管理生命周期

文件：

- `src/components/ActionView/ActionView.tsx`

改动：

- 调用 `renderAgentTab` 时传入 `scrollRestoreKey` 和 `scrollScope`；
- `closeTab` / `closeOtherTabs` / `closeAllTabs` 时删除对应缓存；
- 换会话时不恢复旧会话缓存。

验证：

- 关闭标签后重新打开，不恢复旧观看位置；
- 关闭工作区再打开，不串位置；
- 换会话不串位置。

### 步骤 5：给稳定块加锚点 ID

文件：

- 子 Agent 面板中 `Dialogue` 的可配置外层；
- 必要时给工具调用卡片 props 增加 `scrollAnchorId`。

改动：

- 优先使用已有稳定业务 ID；
- 没有业务 ID 时使用 `turn.key`；
- 不为了锚点引入新的随机 ID。

验证：

- 同一个工具调用在重新挂载后 ID 不变；
- 展开更多轮次后旧锚点仍存在。

### 步骤 6：子会话时间线短缓存

文件：

- `src/hooks/useSessionTimeline.ts`

改动：

- 按 `childSessionId` 缓存最近一次 history / runs；
- 重新挂载时先使用缓存渲染；
- 后台继续同步；
- 用户主动 reload 时强制刷新；
- 会话切换时缓存隔离；
- 浏览器刷新后缓存自然清空。

验证：

- 返回子 Agent 标签页时不先闪骨架；
- 内容高度稳定后再恢复滚动；
- 主动 reload 仍可拿到最新数据。

### 步骤 7：扩展到其他标签页

在子 Agent 场景稳定后，再按同样机制扩展：

- source tab；
- 文件预览 tab；
- 任务预览 tab。

对 PDF / iframe / Office 预览，只在容器允许读取 `scrollTop` 时恢复；不能读取时保持现状，不做虚假承诺。

## 验收标准

必须全部满足：

1. 子 Agent 面板滚到中间位置，点文件进入新标签页，返回后回到同一区域；
2. 不是回到顶部，也不是强制回到底部；
3. 如果切走时就在底部，返回后继续自动跟底；
4. 如果点过“显示更早的轮次”，返回后展开数量不变；
5. 返回后没有明显从空白到内容的刷新闪烁；
6. 关闭标签后重新打开，不恢复旧位置；
7. 关闭工作区或切换会话，不串位置；
8. 刷新浏览器后不恢复旧位置；
9. 相关单测通过；
10. `tsc`、相关 vitest、`pnpm build` 通过。

## 风险与处理

| 风险 | 处理 |
| --- | --- |
| 内容异步加载，恢复时高度不足 | 等内容稳定后恢复，必要时少量重试 |
| 锚点重复或不存在 | 优先业务 ID，其次 `turn.key`；找不到就按比例恢复 |
| 流式内容持续变高 | 只在 `atBottom` 时跟随，否则保持用户当前位置 |
| 缓存串会话 | 缓存键包含 `workspaceStateKey` 和 `tabKey` |
| 滚动监听写得太频繁 | 用 rAF 或 100ms 节流 |
| iframe / PDF 无法读取内部滚动 | 明确不支持，不声称已恢复 |

## 推荐落地顺序

先做步骤 1～4，解决最核心的“回到 38% 位置”问题；再做步骤 5～6，提升锚点稳定性和消除刷新感；最后再考虑步骤 7 扩展到其他标签页。
