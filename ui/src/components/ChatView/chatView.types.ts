export type ActiveRunState = {
  status?: CHAT.RunStatus;
  finishedAt?: string;
};

export type ThrottledStreamController<TValue> = {
  cancel: () => void;
  flush: (force?: boolean) => void;
  schedule: (updater: TValue | ((current: TValue) => TValue), force?: boolean) => void;
  reset: (value: TValue) => void;
};

export type ConversationListKey = "chatList" | "dataChatList";

export type ConversationDraftController<TItem> = {
  conversationId: string;
  getSnapshot: () => CHAT.ConversationHistory;
  replaceLastItem: (item: TItem) => CHAT.ConversationHistory;
  replaceItem: (item: TItem, targetRequestId: string) => CHAT.ConversationHistory;
  commit: (nextConversation: CHAT.ConversationHistory) => void;
};

/**
 * DataAgent 单轮流式运行的运行时上下文。
 *
 * 所有权与顺序约定：`currentChat` 是**当前 draft 的可变工作副本**，只由
 * `updateDataChatFromEvent` 原地修改、`handleError` 重新赋值；每次修改后必须立刻
 * `draftController.replaceLastItem(...)` 并 `commit`，让列表末项跟上。消费者只能把它
 * 当作「本轮流式尚未提交的草稿」，需要持久化状态时应读 `draftController.getSnapshot()`；
 * 一旦草稿被 `commit` 或被新的 run 接管，本字段不再保证与列表末项一致。
 */
export type DataConversationRuntime = {
  draftController: ConversationDraftController<CHAT.DataChatItem>;
  currentChat: CHAT.DataChatItem;
};
