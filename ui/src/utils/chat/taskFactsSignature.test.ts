import { describe, expect, it } from "vitest";

import { combineData, handleTaskData } from "@/utils/chat";
import { buildTaskFactsSignature } from "./renderTasks";
import { findDerivedAgentTaskByToolCallId } from "./taskIdentity";
import { resolveSubAgentDisplay } from "./subagent";

/**
 * 回归：任务事实是**原地**改写的（`tasks.push`），派生重建的判据必须是内容签名，
 * 不能用 `multiAgent` 的引用比对。
 *
 * 曾经用引用比对，同一轮里第二个及以后的工具卡全部被跳过：
 * 实测 facts 1→2 而 view 1→1，时间线永久停在第 1 张卡。
 */
function makeChat(): CHAT.ChatItem {
  return {
    sessionId: "s",
    requestId: "r",
    query: "q",
    files: [],
    multiAgent: { tasks: [] },
    loading: true,
    response: "",
  } as unknown as CHAT.ChatItem;
}

function toolCall(id: string, tool: string): MESSAGE.EventData {
  return {
    messageType: "task",
    taskId: "t1",
    resultMap: {
      messageType: "tool_call",
      toolName: tool
    },
    messageId: id,
    taskIndex: 0,
  } as unknown as MESSAGE.EventData;
}

/**
 * 派生视图是「组 → 容器 → 子卡片」三层，这里数到最内层的子卡片。
 *
 * 数错层级会得到恒为 1 的假结论（容器数），掩盖「第二张卡没进时间线」。
 */
function countCards(groups: unknown[][] | undefined): number {
  let total = 0;
  for (const group of groups || []) {
    for (const container of group || []) {
      const children = (container as { children?: unknown[] })?.children;
      total += children?.length || 0;
    }
  }
  return total;
}

function deepSearchFacts(resultMap: Record<string, unknown>) {
  return buildTaskFactsSignature({
    tasks: [
      [
        {
          messageType: "deep_search",
          resultMap,
        },
      ],
    ],
  } as unknown as MESSAGE.MultiAgent);
}

describe("任务事实签名", () => {
  it("原地 push 会改变签名（引用比对发现不了）", () => {
    const chat = makeChat();
    const multiAgentBefore = chat.multiAgent;

    combineData(toolCall("m1", "workspace_read"), chat);
    const first = chat.taskFactsSignature;

    combineData(toolCall("m2", "workspace_grep"), chat);
    const second = chat.taskFactsSignature;

    // 前提：引用确实没变 —— 这正是引用比对失效的原因。
    expect(chat.multiAgent).toBe(multiAgentBefore);
    expect(first).not.toBe(second);
  });

  it("没有任务事件时签名保持不变", () => {
    const chat = makeChat();
    combineData(toolCall("m1", "workspace_read"), chat);
    const before = chat.taskFactsSignature;

    // 纯正文增量不进任务时间线，签名必须不变，否则派生层会白跑。
    combineData(
      {
        messageType: "task",
        taskId: "t1",
        messageId: "m1",
        resultMap: {
          messageType: "agent_stream",
          result: "正文增量"
        },
      } as unknown as MESSAGE.EventData,
      chat
    );

    expect(chat.taskFactsSignature).toBe(before);
  });

  it("子 Agent 生命周期与进度帧会改变签名，派生卡能拿到 childSessionId", () => {
    const chat = makeChat();
    combineData(
      {
        messageType: "task",
        taskId: "t1",
        messageId: "call-1",
        resultMap: {
          messageType: "tool_call",
          toolName: "Agent",
          toolCallId: "call-1",
          status: "running",
          input: { description: "写测试 HTML", subagent_type: "general-purpose" },
        },
      } as unknown as MESSAGE.EventData,
      chat
    );
    const afterToolCall = chat.taskFactsSignature;

    combineData(
      {
        messageType: "task",
        taskId: "t1",
        messageId: "call-1",
        resultMap: {
          messageType: "subagent_start",
          childSessionId: "sub_agent-1",
          childRequestId: "child-req-1",
          parentToolCallId: "call-1",
          subAgentId: "agent-1",
          subagentType: "general-purpose",
          description: "写测试 HTML",
          status: "running",
        },
      } as unknown as MESSAGE.EventData,
      chat
    );
    const afterStart = chat.taskFactsSignature;
    expect(afterStart).not.toBe(afterToolCall);

    combineData(
      {
        messageType: "task",
        taskId: "t1",
        messageId: "call-1",
        resultMap: {
          messageType: "subagent_progress",
          parentToolUseId: "call-1",
          subAgentId: "agent-1",
          line: "workspace_write",
          text: "正在写文件",
        },
      } as unknown as MESSAGE.EventData,
      chat
    );
    expect(chat.taskFactsSignature).not.toBe(afterStart);

    const derived = handleTaskData(chat, false, chat.multiAgent).currentChat;
    const card = findDerivedAgentTaskByToolCallId(derived, "call-1");
    expect(card).toBeTruthy();
    expect(resolveSubAgentDisplay(card!).childSessionId).toBe("sub_agent-1");
    expect(resolveSubAgentDisplay(card!).childRequestId).toBe("child-req-1");
    expect(resolveSubAgentDisplay(card!).liveText).toBe("正在写文件");
  });

  it("Deep Research 每个阶段都会改变签名", () => {
    const extend = deepSearchFacts({
      messageType: "extend",
      isFinal: false,
      searchFinish: false,
      searchResult: {
        query: ["方向一", "方向二"],
        docs: [],
      },
    });
    const search = deepSearchFacts({
      messageType: "search",
      isFinal: false,
      searchFinish: true,
      searchResult: {
        query: ["方向一", "方向二"],
        docs: [
          [{ link: "https://a.example", title: "来源 A" }],
          [{ link: "https://b.example", title: "来源 B" }],
        ],
      },
    });
    const chapterSummary = deepSearchFacts({
      messageType: "chapter_summary",
      isFinal: false,
      searchFinish: true,
      searchResult: {
        query: ["方向一", "方向二"],
        docs: [
          [{ link: "https://a.example", title: "来源 A" }],
          [{ link: "https://b.example", title: "来源 B" }],
        ],
      },
      chapterId: "C1",
      chapterTitle: "方向一",
      chapterSummary: "章节总结",
      chapterStreaming: true,
      chapters: {
        C1: {
          chapterId: "C1",
          chapterTitle: "方向一",
          queries: ["方向一"],
          docs: [[{ link: "https://a.example", title: "来源 A" }]],
          summary: "章节总结",
          streaming: true,
        },
      },
    });
    const report = deepSearchFacts({
      messageType: "report",
      isFinal: true,
      searchFinish: true,
      answer: "最终报告",
    });

    expect(new Set([extend, search, chapterSummary, report]).size).toBe(4);
  });

  it("Deep Research 来源内容原地变化会改变签名", () => {
    const before = deepSearchFacts({
      messageType: "search",
      searchResult: {
        query: ["方向一"],
        docs: [[{ link: "https://a.example", title: "来源 A" }]],
      },
    });
    const after = deepSearchFacts({
      messageType: "search",
      searchResult: {
        query: ["方向一"],
        docs: [[{ link: "https://b.example", title: "来源 B" }]],
      },
    });

    expect(after).not.toBe(before);
  });

  it("按签名重建时，每张新卡都进入派生视图", () => {
    const chat = makeChat();
    let lastFacts: string | undefined;

    const rebuildIfChanged = () => {
      if (chat.taskFactsSignature === lastFacts) {
        return false;
      }
      lastFacts = chat.taskFactsSignature;
      handleTaskData(chat, true, chat.multiAgent);
      return true;
    };

    combineData(toolCall("m1", "workspace_read"), chat);
    expect(rebuildIfChanged()).toBe(true);
    expect(countCards(chat.tasks as unknown[][])).toBe(1);

    combineData(toolCall("m2", "workspace_grep"), chat);
    expect(rebuildIfChanged()).toBe(true);
    // 关键断言：第二张卡必须出现（旧实现这里是 1）。
    expect(countCards(chat.tasks as unknown[][])).toBe(2);
  });
});
