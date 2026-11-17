import { describe, expect, it } from "vitest";
import type {
  ConversationHistoryPage,
  ConversationRunSummary,
} from "@/services/agentConversation";
import type { SessionRunTimelineSource } from "@/hooks/useSessionTimeline";
import type { AgentTimelineItem } from "@/types/timeline";
import {
  buildSessionTimelineChat,
  buildSessionTimelineRounds,
  projectChatWithTimelineSource,
} from "./sessionTimelineChat";
import { resolveTaskToolCallId } from "./toolCalls";

function eventData(
  resultMap: Record<string, unknown>,
  overrides: Partial<MESSAGE.EventData> = {}
): MESSAGE.EventData {
  return {
    messageOrder: 1,
    messageType: "task",
    messageId: "m1",
    taskId: "t1",
    taskOrder: 1,
    resultMap: resultMap as unknown as MESSAGE.Task,
    ...overrides,
  } as unknown as MESSAGE.EventData;
}

function item(
  itemId: string,
  data: MESSAGE.EventData,
  overrides: Partial<AgentTimelineItem> = {}
): AgentTimelineItem {
  return {
    eventType: "item.completed",
    itemId,
    kind: "ASSISTANT_MESSAGE",
    phase: "INTERMEDIATE",
    itemStatus: "COMPLETED",
    transient: false,
    payload: { eventData: data },
    ...overrides,
  };
}

function run(requestId = "r1", status = "SUCCESS"): ConversationRunSummary {
  return {
    requestId,
    status,
    queryPreview: "问题",
    hasReplay: true,
  };
}

function source(
  items: AgentTimelineItem[],
  status = "SUCCESS"
): SessionRunTimelineSource {
  return { items, status, loaded: true };
}

function history(runs: ConversationRunSummary[]): ConversationHistoryPage {
  return {
    sessionId: "sub-1",
    title: "",
    status: "",
    deepThink: false,
    runCount: runs.length,
    finishedRunCount: 0,
    failedRunCount: 0,
    runs,
  };
}

function findTask(chat: CHAT.ChatItem, toolCallId: string): CHAT.Task | undefined {
  const visit = (task: CHAT.Task | undefined): CHAT.Task | undefined => {
    if (!task) {
      return undefined;
    }
    if (resolveTaskToolCallId(task) === toolCallId) {
      return task;
    }
    for (const child of task.children || []) {
      const hit = visit(child);
      if (hit) {
        return hit;
      }
    }
    return undefined;
  };
  for (const group of chat.tasks || []) {
    for (const container of group || []) {
      const hit = visit(container as CHAT.Task);
      if (hit) {
        return hit;
      }
    }
  }
  return undefined;
}

describe("sessionTimelineChat", () => {
  it("only run-final owns the conclusion", () => {
    const internal = eventData({
      messageType: "result",
      result: "WebFetch 内部提取结果",
      isFinal: true,
    });
    const final = eventData({
      messageType: "result",
      result: "子 Agent 最终答复",
      isFinal: true,
    });
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run("r1", "RUNNING"),
      source: source([
        item("llm:15636", internal),
        item("run:r1:final", final, { phase: "FINAL", eventType: "run.completed" }),
      ], "RUNNING"),
    });

    expect(chat.conclusion?.result).toBe("子 Agent 最终答复");
  });

  it("canonical process item does not become an early conclusion while running", () => {
    const ask = eventData({
      messageType: "result",
      result: "WebFetch 内部提取结果",
      isFinal: true,
    });
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run("r1", "RUNNING"),
      source: source([item("llm:15636", ask)], "RUNNING"),
    });

    expect(chat.conclusion).toBeUndefined();
    expect(chat.loading).toBe(true);
  });

  it("legacy replay result cannot become an early conclusion while running", () => {
    const ask = eventData({
      messageType: "result",
      result: "WebFetch 内部提取结果",
      isFinal: true,
    });
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run("r1", "RUNNING"),
      source: {
        items: [],
        replayFrames: [{
          reqId: "r1",
          status: "running",
          finished: false,
          resultMap: { eventData: ask },
        }],
        status: "RUNNING",
        loaded: true,
      },
    });

    expect(chat.conclusion).toBeUndefined();
    expect(chat.loading).toBe(true);
  });

  it("canonical task_summary cannot become a fallback conclusion", () => {
    const summary = eventData({
      messageType: "task_summary",
      taskSummary: "过程摘要",
      result: "过程摘要",
      isFinal: false,
    });
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run("r1", "SUCCESS"),
      source: source([item("summary:1", summary, {
        phase: "SUMMARY",
      })], "SUCCESS"),
    });

    expect(chat.conclusion).toBeUndefined();
  });

  it("isolated session detaches an external parent Agent tag but keeps its tools", () => {
    const tool = eventData({
      messageType: "tool_result",
      toolName: "workspace_write",
      toolCallId: "call-1",
      parentToolUseId: "parent-agent-card",
      status: "success",
      result: "写入完成",
    });
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run(),
      source: source([item("tool:call-1", tool, { kind: "TOOL_CALL" })]),
    });

    expect(findTask(chat, "call-1")).toBeDefined();
  });

  it("committed ARTIFACT without identity is not rendered as a File card", () => {
    const file = eventData({ messageType: "file", fileListOnly: false });
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run(),
      source: source([item("artifact:orphan", file, {
        kind: "ARTIFACT",
        parentItemId: null,
      })]),
    });

    expect(JSON.stringify(chat.tasks)).not.toContain("\"file\"");
  });

  it("legacy summary is only a fallback when no canonical source is loaded", () => {
    const legacyRun = { ...run(), finalSummaryText: "账本终态摘要" };
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: legacyRun,
    });

    expect(chat.conclusion?.result).toBe("账本终态摘要");
  });

  it("uses the ledger run summary only when a terminal canonical source has no run-final", () => {
    const terminalRun = { ...run("r1", "RUNNING"), finalSummaryText: "账本终态摘要" };
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: terminalRun,
      source: source([], "SUCCESS"),
    });

    expect(chat.conclusion?.result).toBe("账本终态摘要");
  });

  it("builds one chat per run and keeps pending runs at the tail once", () => {
    const rounds = buildSessionTimelineRounds({
      sessionId: "sub-1",
      history: history([run("r1")]),
      sources: {},
      pendingRun: { requestId: "r2", query: "刚发的" },
    });

    expect(rounds.map((chat) => chat.requestId)).toEqual(["r1", "r2"]);
    expect(rounds[1]?.query).toBe("刚发的");
    expect(rounds[1]?.loading).toBe(true);
  });

  it("uses canonical source status instead of stale history status", () => {
    const chat = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run("r1", "RUNNING"),
      source: source([], "SUCCESS"),
    });

    expect(chat.loading).toBe(false);
    expect(chat.metrics?.status).toBe("SUCCESS");
  });

  it("reprojects a live control-flow chat without losing its session metadata", () => {
    const liveChat = {
      ...run("r1", "RUNNING"),
      sessionId: "main-1",
      query: "主 Agent 问题",
      contextUsage: { max: 1000, promptTokens: 120 },
      tip: "运行中",
      tasks: [[{
        id: "stale",
        messageId: "stale",
        messageType: "tool_result",
        result: "old",
      }]] as unknown as CHAT.Task[][],
      multiAgent: { tasks: [] },
    } as unknown as CHAT.ChatItem;
    const final = eventData({
      messageType: "result",
      result: "主 Agent 最终答复",
      isFinal: true,
    });
    const projected = projectChatWithTimelineSource(
      liveChat,
      source([
        item("run:r1:final", final, { phase: "FINAL", eventType: "run.completed" }),
      ])
    );

    expect(projected.sessionId).toBe("main-1");
    expect(projected.contextUsage?.promptTokens).toBe(120);
    expect(projected.conclusion?.result).toBe("主 Agent 最终答复");
    expect(JSON.stringify(projected.tasks)).not.toContain("\"stale\"");
  });

  it("keeps the live conclusion until the canonical run-final arrives", () => {
    const tool = eventData({
      messageType: "tool_result",
      toolName: "workspace_write",
      toolCallId: "call-1",
      status: "success",
      result: "画布已发布",
    });
    const liveChat = {
      ...run("r1", "SUCCESS"),
      sessionId: "main-1",
      query: "生成画布",
      files: [],
      forceStop: false,
      loading: false,
      tasks: [],
      timeline: [],
      multiAgent: { tasks: [] },
      conclusion: {
        id: "live-final",
        messageId: "live-final",
        messageType: "result",
        result: "控制流已经拿到的终答",
        isFinal: true,
      },
    } as unknown as CHAT.ChatItem;

    const projected = projectChatWithTimelineSource(
      liveChat,
      source([
        item("tool:call-1", tool, {
          kind: "TOOL_CALL",
          itemStatus: "COMPLETED",
        }),
      ], "SUCCESS")
    );

    expect(projected.conclusion?.result).toBe("控制流已经拿到的终答");
  });

  it("main and child Agent share the same canonical task structure", () => {
    const tool = eventData({
      messageType: "tool_result",
      toolName: "workspace_write",
      toolCallId: "call-1",
      status: "success",
      result: "写入完成",
    });
    const final = eventData({
      messageType: "result",
      result: "最终答复",
      isFinal: true,
    });
    const items = [
      item("tool:call-1", tool, {
        kind: "TOOL_CALL",
        itemStatus: "COMPLETED",
      }),
      item("run:r1:final", final, {
        kind: "ASSISTANT_MESSAGE",
        phase: "FINAL",
        eventType: "run.completed",
      }),
    ];
    const child = buildSessionTimelineChat({
      sessionId: "sub-1",
      run: run("r1", "SUCCESS"),
      source: source(items, "SUCCESS"),
    });
    const main = projectChatWithTimelineSource({
      sessionId: "main-1",
      requestId: "r1",
      query: "同一个问题",
      files: [],
      forceStop: false,
      loading: false,
      tasks: [],
      timeline: [],
      multiAgent: { tasks: [] },
    } as CHAT.ChatItem, source(items, "SUCCESS"));

    expect(main.tasks).toEqual(child.tasks);
    expect(main.conclusion?.result).toBe(child.conclusion?.result);
  });
});
