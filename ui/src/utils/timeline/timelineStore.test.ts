import { beforeEach, describe, expect, it } from "vitest";
import {
  TimelineStore,
  isConclusionEligibleLiveFrame,
  isRunFinalTimelineItem,
} from "./timelineStore";
import type { TimelinePatch } from "@/types/timeline";
import {
  getTimelineDiagnosticsSnapshot,
  resetTimelineDiagnostics,
} from "./timelineDiagnostics";

describe("TimelineStore", () => {
  beforeEach(() => {
    resetTimelineDiagnostics();
  });

  const patch = (
    overrides: Partial<TimelinePatch> = {},
  ): TimelinePatch => ({
    protocolVersion: "timeline.v1",
    runId: "run-1",
    sessionId: "session-1",
    eventId: "event-1",
    seq: 1,
    operation: "upsert",
    itemId: "tool:call-1",
    kind: "TOOL_CALL",
    status: "RUNNING",
    ...overrides,
  });

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

  it("Deep Search 报告的非终态帧按显式增量合并，终态快照直接替换", () => {
    const store = new TimelineStore();
    const report = (
      answer: string,
      isFinal: boolean,
      eventId: string,
    ) => ({
      eventType: "item.upserted" as const,
      eventId,
      itemId: "tool:call-deep",
      kind: "TOOL_CALL" as const,
      seq: eventId.endsWith("2") ? 2 : eventId.endsWith("3") ? 3 : 1,
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
              messageType: "report",
              answer,
              isFinal,
            },
          },
        },
      },
    });

    store.upsertMany([report("第一段", false, "report-1") as never]);
    store.upsertMany([report("第二段", false, "report-2") as never]);
    expect(
      (
        (
          store.values()[0].payload?.eventData?.resultMap as unknown as Record<string, unknown>
        ).resultMap as Record<string, unknown>
      ).answer
    ).toBe("第一段第二段");

    store.upsertMany([report("完整报告", true, "report-3") as never]);
    expect(
      (
        (
          store.values()[0].payload?.eventData?.resultMap as unknown as Record<string, unknown>
        ).resultMap as Record<string, unknown>
      ).answer
    ).toBe("完整报告");
  });

  it("TimelinePatch 的 eventId 幂等，重复帧不会重复写卡", () => {
    const store = new TimelineStore();
    store.applyPatch(patch());
    store.applyPatch(patch());

    expect(store.values()).toHaveLength(1);
    expect(store.values()[0].itemId).toBe("tool:call-1");
  });

  it("直接 upsert canonical item 时 eventId 同样幂等", () => {
    const store = new TimelineStore();
    const item = {
      eventId: "event-live-1",
      itemId: "tool:call-live",
      kind: "TOOL_CALL" as const,
      delta: "一次",
    };
    store.upsertMany([item]);
    store.upsertMany([{ ...item, delta: "重复" }]);

    expect(store.values()).toHaveLength(1);
    expect(store.values()[0].delta).toBe("一次");
  });

  it("同一 itemId 的旧 seq 不得覆盖新 seq", () => {
    const store = new TimelineStore();
    store.applyPatch(patch({ eventId: "event-new", seq: 3 }));
    store.applyPatch(patch({
      eventId: "event-old",
      seq: 1,
      status: "COMPLETED",
    }));

    expect(store.values()[0].itemStatus).toBe("RUNNING");
  });

  it("终态不会被迟到 RUNNING 帧静默回退，并记录非法迁移", () => {
    const store = new TimelineStore();
    store.applyPatch(patch({
      eventId: "event-completed",
      status: "COMPLETED",
    }));
    store.applyPatch(patch({
      eventId: "event-late-running",
      seq: 2,
      status: "RUNNING",
    }));

    expect(store.values()[0].itemStatus).toBe("COMPLETED");
    expect(getTimelineDiagnosticsSnapshot().counters.illegal_transition).toBe(1);
  });

  it("同一 eventId 指向不同 itemId 时记录 churn", () => {
    const store = new TimelineStore();
    store.applyPatch(patch());
    store.applyPatch(patch({ itemId: "tool:call-other" }));

    expect(store.values()).toHaveLength(1);
    expect(getTimelineDiagnosticsSnapshot().counters.duplicate_event).toBe(1);
    expect(getTimelineDiagnosticsSnapshot().counters.item_id_churn).toBe(1);
  });

  it("replace 是完整快照，不会保留上一版正文", () => {
    const store = new TimelineStore();
    store.applyPatch(patch({
      eventId: "event-append",
      operation: "append",
      delta: "旧内容",
    }));
    store.applyPatch(patch({
      eventId: "event-replace",
      seq: 2,
      operation: "replace",
      delta: "完整内容",
    }));

    expect(store.values()[0].delta).toBe("完整内容");
  });

  it("remove 会移除同一 itemId，不再出现在渲染结果中", () => {
    const store = new TimelineStore();
    store.applyPatch(patch());
    store.applyPatch(patch({
      eventId: "event-remove",
      seq: 2,
      operation: "remove",
    }));

    expect(store.values()).toHaveLength(0);
  });
});
