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
});
