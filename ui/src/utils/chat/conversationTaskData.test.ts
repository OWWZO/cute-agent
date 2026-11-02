import { describe, expect, it } from "vitest";

import { buildConversationTaskData } from "@/utils/chat";

function createChat(overrides: Partial<CHAT.ChatItem> = {}): CHAT.ChatItem {
  return {
    sessionId: "s1",
    requestId: "r1",
    query: "q",
    files: [],
    loading: true,
    tasks: [],
    timeline: [],
    multiAgent: {
      tasks: [
        [
          {
            messageType: "tool_call",
            messageId: "m1",
            messageTime: "1",
            resultMap: { toolName: "Read", isFinal: true },
          } as unknown as MESSAGE.Task,
        ],
      ],
    },
    ...overrides,
  } as unknown as CHAT.ChatItem;
}

describe("buildConversationTaskData 缓存", () => {
  it("纯正文更新复用已派生的任务树", () => {
    const chat = createChat();
    const first = buildConversationTaskData(chat, false);

    const streamed = {
      ...chat,
      response: "流式正文",
      tip: "正在整理"
    } as CHAT.ChatItem;
    const second = buildConversationTaskData(streamed, false);

    expect(second.currentChat.tasks).toBe(first.currentChat.tasks);
    expect(second.currentChat.response).toBe("流式正文");
    expect(second.currentChat.tip).toBe("正在整理");
  });

  it("任务事实变化时重建派生树", () => {
    const chat = createChat();
    buildConversationTaskData(chat, false);

    chat.multiAgent.tasks = [
      ...chat.multiAgent.tasks,
      [
        {
          messageType: "tool_call",
          messageId: "m2",
          messageTime: "2",
          resultMap: { toolName: "Grep", isFinal: true },
        } as unknown as MESSAGE.Task,
      ],
    ];
    const next = buildConversationTaskData(chat, false);
    const cards = next.currentChat.tasks.flatMap((group) =>
      group.flatMap((container) => container.children || [])
    );
    expect(cards).toHaveLength(2);
  });
});
