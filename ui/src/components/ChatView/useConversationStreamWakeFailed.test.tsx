import { describe, expect, it, vi } from "vitest";

import { planWakeFailedSnapshot } from "./useConversationStream";

function conversation(id: string, requestId: string): CHAT.ConversationHistory {
  return {
    id,
    sessionId: `session-${id}`,
    chatList: [
      {
        requestId,
        query: `当前会话 ${id}`,
        loading: false,
        multiAgent: { tasks: [] },
        metrics: { status: "SUCCESS" },
      } as unknown as CHAT.ChatItem,
    ],
  } as CHAT.ConversationHistory;
}

describe("后台唤醒失败事件", () => {
  it("切换到别的会话后，旧会话 wake_failed 不提交当前会话", () => {
    const currentConversation = conversation("c-current", "req-old");
    const oldConversation = conversation("c-old", "req-old");
    const snapshots = new Map<string, CHAT.ConversationHistory>();
    const currentChatList = currentConversation.chatList;
    const commit = vi.fn();

    const snapshot = planWakeFailedSnapshot({
      conversationId: "c-old",
      requestId: "req-old",
      snapshots,
      currentConversation,
    });
    if (snapshot) {
      commit({
        ...snapshot,
        id: "c-old",
        chatList: (snapshot.chatList || []).filter(
          (item) => item.requestId !== "req-old"
        ),
      });
    }

    expect(snapshot).toBeUndefined();
    expect(commit).not.toHaveBeenCalled();
    expect(currentConversation.chatList).toBe(currentChatList);
    snapshots.set("c-old", oldConversation);
    expect(
      planWakeFailedSnapshot({
        conversationId: "c-old",
        requestId: "req-old",
        snapshots,
        currentConversation,
      })
    ).toBe(oldConversation);
  });
});
