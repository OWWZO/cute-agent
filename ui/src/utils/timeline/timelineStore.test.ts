import { describe, expect, it } from "vitest";
import {
  TimelineStore,
  isConclusionEligibleLiveFrame,
  isRunFinalTimelineItem,
} from "./timelineStore";

describe("TimelineStore", () => {
  it("按 itemId upsert，并用 orderKey/subOrder 排序", () => {
    const store = new TimelineStore([
      { itemId: "tool:b", kind: "TOOL_CALL", orderKey: 2, subOrder: 0 },
      { itemId: "tool:a", kind: "TOOL_CALL", orderKey: 1, subOrder: 0 },
    ]);
    store.upsertMany([
      { itemId: "tool:a", kind: "TOOL_CALL", itemStatus: "COMPLETED", orderKey: 1 },
    ]);

    expect(store.values().map((item) => item.itemId)).toEqual(["tool:a", "tool:b"]);
    expect(store.values()[0].itemStatus).toBe("COMPLETED");
  });

  it("只有 run-final 是合法终答", () => {
    expect(isRunFinalTimelineItem({
      itemId: "run:r1:final",
      kind: "ASSISTANT_MESSAGE",
      phase: "FINAL",
    })).toBe(true);
    expect(isRunFinalTimelineItem({
      itemId: "llm:15636",
      kind: "ASSISTANT_MESSAGE",
      phase: "INTERMEDIATE",
    })).toBe(false);
  });

  it("带 canonical itemId 的非终答 result 不获得结论资格", () => {
    expect(isConclusionEligibleLiveFrame({
      itemId: "llm:15636",
      kind: "ASSISTANT_MESSAGE",
      phase: "INTERMEDIATE",
      resultMap: { eventData: { resultMap: { messageType: "result" } } },
    })).toBe(false);
  });

  it("没有稳定 itemId 的项不能伪造时间线身份", () => {
    const store = new TimelineStore([
      { eventId: "session:1", seq: 1, kind: "TOOL_CALL" },
      { itemId: "tool:stable", kind: "TOOL_CALL" },
    ]);

    expect(store.values().map((item) => item.itemId)).toEqual(["tool:stable"]);
  });

  it("同一章节的流式总结帧按累计文本合并", () => {
    const store = new TimelineStore();
    const chapter = (summary: string, streaming: boolean) => ({
      eventType: "item.upserted" as const,
      itemId: "tool:call-deep",
      kind: "TOOL_CALL" as const,
      payload: {
        eventData: {
          messageType: "task",
          messageId: "msg-deep",
          taskId: "task-deep",
          taskOrder: 1,
          messageOrder: 1,
          resultMap: {
            messageType: "deep_search",
            messageId: "msg-deep",
            messageTime: "1",
            resultMap: {
              messageType: "chapter_summary",
              chapterId: "C1",
              chapterTitle: "第一章",
              chapterOrder: 1,
              chapterSummary: summary,
              answer: summary,
              chapterStreaming: streaming,
            },
          },
        },
      },
    });

    store.upsertMany([chapter("第一段", true) as never]);
    store.upsertMany([chapter("第一段第二段", true) as never]);

    const payload = store.values()[0].payload?.eventData?.resultMap as
      | Record<string, unknown>
      | undefined;
    const stage = payload?.resultMap as Record<string, unknown> | undefined;
    expect(stage?.chapterSummary).toBe("第一段第二段");
    expect(stage?.chapterStreaming).toBe(true);
  });

  it("章节总结帧不会覆盖已到达的检索来源", () => {
    const store = new TimelineStore();
    const deepSearch = (stage: Record<string, unknown>) => ({
      eventType: "item.upserted" as const,
      itemId: "tool:call-deep",
      kind: "TOOL_CALL" as const,
      payload: {
        eventData: {
          messageType: "task",
          messageId: "msg-deep",
          taskId: "task-deep",
          taskOrder: 1,
          messageOrder: 1,
          resultMap: {
            messageType: "deep_search",
            messageId: "msg-deep",
            messageTime: "1",
            resultMap: stage,
          },
        },
      },
    });

    store.upsertMany([deepSearch({
      messageType: "search",
      searchResult: {
        query: ["查询一"],
        docs: [[{ title: "来源一", link: "https://example.com/a" }]],
      },
    }) as never]);
    store.upsertMany([deepSearch({
      messageType: "chapter_summary",
      chapterId: "C1",
      chapterTitle: "第一章",
      chapterOrder: 1,
      chapterSummary: "章节总结",
      answer: "章节总结",
      searchResult: {
        query: ["查询一"],
        docs: [],
      },
    }) as never]);

    const history = store.values()[0].payload?.eventDataHistory || [];
    const search = history.find((event) => {
      const payload = event.resultMap as unknown as Record<string, unknown> | undefined;
      return (payload?.resultMap as Record<string, unknown> | undefined)?.messageType === "search";
    });
    const searchPayload = search?.resultMap as unknown as Record<string, unknown> | undefined;
    const searchResult = (searchPayload?.resultMap as Record<string, unknown> | undefined)
      ?.searchResult as Record<string, unknown> | undefined;
    expect(searchResult?.docs).toEqual([
      [{ title: "来源一", link: "https://example.com/a" }],
    ]);
  });

  it("同一时间线项的后续快照不会丢掉已有的 Deep Search 阶段历史", () => {
    const deepSearch = (stage: Record<string, unknown>, orderKey: number) => ({
      eventType: "item.upserted" as const,
      itemId: "tool:call-deep",
      kind: "TOOL_CALL" as const,
      orderKey,
      payload: {
        eventData: {
          messageType: "task",
          messageId: "msg-deep",
          taskId: "task-deep",
          taskOrder: 1,
          messageOrder: orderKey,
          resultMap: {
            messageType: "deep_search",
            messageId: "msg-deep",
            messageTime: String(orderKey),
            resultMap: stage,
          },
        },
      },
    });

    const store = new TimelineStore();
    store.upsertMany([deepSearch({
      messageType: "search",
      searchResult: {
        query: ["查询一"],
        docs: [[{ title: "来源一", link: "https://example.com/a" }]],
      },
    }, 1) as never]);
    store.upsertMany([deepSearch({
      messageType: "chapter_summary",
      chapterId: "C1",
      chapterTitle: "第一章",
      chapterOrder: 1,
      chapterSummary: "第一章总结",
      answer: "第一章总结",
    }, 2) as never]);
    store.upsertMany([deepSearch({
      messageType: "report",
      isFinal: true,
      answer: "最终报告",
    }, 3) as never]);

    const history = store.values()[0]?.payload?.eventDataHistory || [];
    expect(history.map((event) => {
      const outer = event.resultMap as unknown as Record<string, unknown>;
      const nested = outer.resultMap as Record<string, unknown> | undefined;
      return nested?.messageType;
    })).toEqual(["search", "chapter_summary", "report"]);
  });
});
