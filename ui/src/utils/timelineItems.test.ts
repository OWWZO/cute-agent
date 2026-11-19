import { describe, expect, it } from "vitest";
import type { ConversationReplayFrame } from "@/services/agentConversation";
import {
  isRunTerminal,
  resolveRunReplayFrames,
  timelineEnvelopeToLegacy,
  timelineItemsToReplayFrames,
} from "./timelineItems";
import { TimelineStore } from "./timeline/timelineStore";

function eventData(messageType: string, resultMap: Record<string, unknown>): MESSAGE.EventData {
  return {
    taskId: "task-1",
    taskOrder: 1,
    messageOrder: 1,
    messageType,
    messageId: "msg-1",
    resultMap: {
      requestId: "req-1",
      messageId: "msg-1",
      messageTime: "0",
      messageType,
      finish: false,
      isFinal: true,
      id: "task-1",
      resultMap: {},
      ...resultMap,
    } as MESSAGE.Task,
  };
}

describe("timelineItems", () => {
  it("delta upsert keeps item metadata that the delta frame omitted", () => {
    const store = new TimelineStore([{
      eventType: "item.upserted",
      itemId: "tool:call-1",
      kind: "TOOL_CALL",
      phase: "INTERMEDIATE",
      itemStatus: "RUNNING",
      orderKey: 12,
      subOrder: 1,
      payload: {
        eventData: eventData("task", {
          messageType: "deep_search",
          toolCallId: "call-1",
        }),
      },
    }]);
    store.upsertMany([{
      eventType: "item.delta",
      itemId: "tool:call-1",
      delta: "下一段",
      responseAll: "完整输出",
    }]);

    const updated = store.values()[0];
    expect(updated.kind).toBe("TOOL_CALL");
    expect(updated.phase).toBe("INTERMEDIATE");
    expect(updated.itemStatus).toBe("RUNNING");
    expect(updated.orderKey).toBe(12);
    expect(updated.subOrder).toBe(1);
    expect(updated.responseAll).toBe("完整输出");
  });

  it("同一时间线项后续帧不能改写首次分配的 orderKey", () => {
    const store = new TimelineStore([{
      eventType: "item.upserted",
      itemId: "tool:call-1",
      kind: "TOOL_CALL",
      orderKey: 12,
      payload: {
        eventData: eventData("task", {
          messageType: "deep_search",
          toolCallId: "call-1",
        }),
      },
    }]);
    store.upsertMany([{
      eventType: "item.delta",
      itemId: "tool:call-1",
      orderKey: 99,
    }]);

    expect(store.values()[0].orderKey).toBe(12);
  });

  it("maps item.delta back to the existing canonical renderer envelope", () => {
    const event = eventData("task", {
      messageType: "agent_stream",
      result: "你好",
    });

    const normalized = timelineEnvelopeToLegacy({
      protocolVersion: "2",
      eventType: "item.delta",
      itemId: "llm:42",
      delta: "你好",
      payload: { eventData: event },
    });

    expect(normalized.packageType).toBe("task");
    expect(normalized.resultMap).toMatchObject({ eventData: event });
  });

  it("only treats completed, failed and stopped as terminal run events", () => {
    expect(isRunTerminal("run.completed")).toBe(true);
    expect(isRunTerminal("run.failed")).toBe(true);
    expect(isRunTerminal("run.stopped")).toBe(true);
    expect(isRunTerminal("run.wake")).toBe(false);
    expect(isRunTerminal("run.wake_failed")).toBe(false);
  });

  it("maps run.completed to the existing root result envelope", () => {
    const event = eventData("task", {
      messageType: "result",
      result: "最终答案",
    });

    const normalized = timelineEnvelopeToLegacy({
      eventType: "run.completed",
      payload: { eventData: event },
    });

    expect(normalized.packageType).toBe("result");
    expect(normalized.finished).toBe(true);
    expect(normalized.resultMap).toMatchObject({ eventData: event });
  });

  it("does not treat stream.closed as run completion", () => {
    const normalized = timelineEnvelopeToLegacy({
      eventType: "stream.closed",
      finished: false,
    });

    expect(normalized.packageType).toBe("stream_settle");
    expect(normalized.finished).toBe(false);
  });

  it("keeps the explicit transport finished flag for stream.closed", () => {
    const normalized = timelineEnvelopeToLegacy({
      eventType: "stream.closed",
      finished: true,
    });

    expect(normalized.packageType).toBe("stream_settle");
    expect(normalized.finished).toBe(true);
  });

  it("keeps the transport finished flag for nested run-like events", () => {
    const normalized = timelineEnvelopeToLegacy({
      eventType: "run.completed",
      finished: false,
      payload: { eventData: eventData("task", { messageType: "result" }) },
    });

    expect(normalized.finished).toBe(false);
  });

  it("keeps renderable timeline frames when a directory-only item has no eventData", () => {
    const replayFrame: ConversationReplayFrame = {
      reqId: "req-1",
      status: "success",
      finished: true,
      resultMap: {
        agentType: "history",
        multiAgent: {},
        eventData: eventData("task", { messageType: "result", result: "最终答案" }),
      },
    };

    const frames = resolveRunReplayFrames({
      timelineItems: [
        {
          eventType: "item.completed",
          runId: "req-1",
          itemStatus: "COMPLETED",
          payload: {
            eventData: eventData("task", { messageType: "result", result: "最终答案" }),
          },
        },
        { eventType: "item.completed", payload: {} },
      ],
      replayFrames: [replayFrame],
    });

    expect(frames).toHaveLength(1);
    expect(frames[0].resultMap.eventData?.resultMap.messageType).toBe("result");
  });

  it("committed ARTIFACT without artifact identity never becomes a File card", () => {
    const frames = timelineItemsToReplayFrames([{
      eventType: "item.completed",
      kind: "ARTIFACT",
      itemId: "artifact:orphan",
      parentItemId: null,
      itemStatus: "COMPLETED",
      transient: false,
      payload: {
        eventData: eventData("task", {
          messageType: "file",
          fileListOnly: false,
        }),
      },
    }]);

    expect(frames).toEqual([]);
  });

  it("live transient file frame remains visible before ledger settlement", () => {
    const frames = timelineItemsToReplayFrames([{
      eventType: "item.completed",
      kind: "ARTIFACT",
      itemId: "artifact:call-1",
      parentItemId: "tool:call-1",
      itemStatus: "COMPLETED",
      transient: true,
      payload: {
        eventData: eventData("task", {
          messageType: "file",
        }),
      },
    }]);

    expect(frames).toHaveLength(1);
  });

  it("committed ARTIFACT requires both artifact and tool invocation identity", () => {
    const base = {
      eventType: "item.completed" as const,
      kind: "ARTIFACT" as const,
      itemId: "artifact:23",
      parentItemId: "tool:call-1",
      itemStatus: "COMPLETED" as const,
      transient: false,
      payload: {
        artifactId: 23,
        toolInvocationId: 7,
        eventData: eventData("task", {
          messageType: "file",
          fileName: "report.md",
        }),
      },
    };

    expect(timelineItemsToReplayFrames([base])).toHaveLength(1);
    expect(timelineItemsToReplayFrames([{
      ...base,
      payload: {
        ...base.payload,
        toolInvocationId: undefined,
      },
    }])).toEqual([]);
  });

  it("falls back to replay frames only when no timeline item is renderable", () => {
    const replayFrame: ConversationReplayFrame = {
      reqId: "req-1",
      status: "success",
      finished: true,
      resultMap: {
        agentType: "history",
        multiAgent: {},
        eventData: eventData("task", { messageType: "result", result: "最终答案" }),
      },
    };

    expect(resolveRunReplayFrames({
      timelineItems: [{ eventType: "item.completed", payload: {} }],
      replayFrames: [replayFrame],
    })).toEqual([replayFrame]);
  });

  it("converts history timeline items to replay frames in order", () => {
    const frames = timelineItemsToReplayFrames([
      {
        eventType: "item.completed",
        itemId: "tool:call-1",
        runId: "req-1",
        orderKey: 1,
        status: "COMPLETED",
        payload: {
          eventData: eventData("task", {
            messageType: "tool_result",
            toolCallId: "call-1",
          }),
        },
      },
      {
        eventType: "run.completed",
        itemId: "run:req-1:final",
        runId: "req-1",
        orderKey: 2,
        status: "COMPLETED",
        payload: {
          eventData: eventData("task", {
            messageType: "result",
            result: "最终答案",
          }),
        },
      },
    ]);

    expect(frames).toHaveLength(2);
    expect(frames[0].resultMap.eventData?.resultMap.messageType).toBe("tool_result");
    expect(frames[1].finished).toBe(true);
  });

  it("keeps a stopped run as stopped during history replay", () => {
    const frames = timelineItemsToReplayFrames([
      {
        eventType: "run.stopped",
        itemId: "run:req-1:final",
        runId: "req-1",
        itemStatus: "CANCELLED",
        payload: {
          eventData: eventData("task", {
            messageType: "result",
            result: "已停止",
          }),
        },
      },
    ]);

    expect(frames[0].status).toBe("stopped");
    expect(frames[0].finished).toBe(true);
  });

  it("keeps a waiting item in progress instead of marking it successful", () => {
    const frames = timelineItemsToReplayFrames([
      {
        eventType: "item.upserted",
        itemId: "hitl:ask-1",
        runId: "req-1",
        itemStatus: "WAITING_INPUT",
        payload: {
          eventData: eventData("task", {
            messageType: "ask_user_question",
          }),
        },
      },
    ]);

    expect(frames[0].status).toBe("waiting_input");
  });

  it("live responseAll 覆盖单帧 eventData 的快照正文", () => {
    const frames = timelineItemsToReplayFrames([{
      eventType: "item.delta",
      itemId: "llm:1",
      runId: "req-1",
      responseAll: "完整正文",
      payload: {
        eventData: eventData("task", {
          messageType: "agent_stream",
          result: "第一段",
        }),
      },
    }]);

    expect(frames[0].resultMap.eventData?.resultMap.result).toBe("完整正文");
  });

  it("deep_search 的多个阶段快照在同一条工具项下不会互相覆盖", () => {
    const events = [
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "extend",
          isFinal: false,
          searchFinish: false,
          searchResult: {
            query: ["子问题一"],
            docs: [],
          },
        },
      }),
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "chapter_summary",
          isFinal: false,
          chapterId: "C1",
          chapterTitle: "子问题一",
          chapterSummary: "章节总结",
          searchResult: {
            query: ["子问题一"],
            docs: [],
          },
        },
      }),
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "report",
          isFinal: true,
          answer: "完整报告",
        },
      }),
    ];

    const frames = timelineItemsToReplayFrames(
      events.map((event, index) => ({
        eventType: "item.delta" as const,
        itemId: "tool:call-1",
        kind: "TOOL_CALL" as const,
        phase: "INTERMEDIATE" as const,
        itemStatus: "RUNNING" as const,
        orderKey: index + 1,
        payload: { eventData: event },
      }))
    );

    expect(frames.map((frame) => {
      const nested = frame.resultMap.eventData?.resultMap.resultMap as
        | Record<string, unknown>
        | undefined;
      return nested?.messageType;
    })).toEqual(["extend", "chapter_summary", "report"]);
  });

  it("deep_search 搜索快照合并 query，报告增量合并后再由最终快照覆盖", () => {
    const events = [
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "search",
          searchResult: {
            query: ["query-a"],
            docs: [[{ title: "A", link: "https://example.com/a" }]],
          },
        },
      }),
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "search",
          searchResult: {
            query: ["query-b"],
            docs: [[{ title: "B", link: "https://example.com/b" }]],
          },
        },
      }),
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "report",
          isFinal: false,
          answer: "前半",
        },
      }),
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "report",
          isFinal: false,
          answer: "后半",
        },
      }),
      eventData("task", {
        messageType: "deep_search",
        toolCallId: "call-1",
        resultMap: {
          messageType: "report",
          isFinal: true,
          answer: "完整报告",
        },
      }),
    ];

    const frames = timelineItemsToReplayFrames(
      events.map((event, index) => ({
        eventType: "item.delta" as const,
        itemId: "tool:call-1",
        kind: "TOOL_CALL" as const,
        phase: "INTERMEDIATE" as const,
        itemStatus: "RUNNING" as const,
        orderKey: index + 1,
        payload: { eventData: event },
      }))
    );
    const stages = frames.map((frame) =>
      frame.resultMap.eventData?.resultMap.resultMap as
        | Record<string, unknown>
        | undefined
    );

    expect(stages.map((stage) => stage?.messageType)).toEqual(["search", "report"]);
    expect(stages[0]?.searchResult).toMatchObject({
      query: ["query-a", "query-b"],
    });
    expect(stages[1]?.answer).toBe("完整报告");
  });
});
