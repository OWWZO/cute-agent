export type { DeepSearchCardItem as SearchListItem } from "@/types/deepSearch";

/**
 * 面板可直接消费的任务。
 *
 * 为什么是联合而不是单一形状：`CHAT.Task` 把实时事件的 `resultMap.searchResult.docs`
 * 拍平成了 `MESSAGE.Doc[]`，而历史回放任务仍是 `MESSAGE.Task` 的 `Doc[][]`，
 * 两者的 `resultMap` 互不兼容，无法用一个显式形状同时表达。联合因此是必要的：
 * 只读公共字段的消费方直接工作；需要 `CHAT.Task` 特有成员的地方（`children` 等）
 * 应在读取前用 `isTaskTab` 之类的缩窄或结构守卫，而不是 `as unknown as`。
 */
export type PanelItemType = CHAT.Task | MESSAGE.Task
