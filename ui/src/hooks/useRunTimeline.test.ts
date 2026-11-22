import { describe, expect, it } from "vitest";
import type { AgentTimelineItem } from "@/types/timeline";
import {
  isTerminalRunControl,
  isWaitingInputIdleControl,
  resolveRunFinalItemStatus,
  shouldObserveRunTimeline,
  toTimelineItem,
} from "./useRunTimeline";

describe("useRunTimeline canonical envelope", () => {
  it("separates committed order from live transport cursor", () => {
    const item = toTimelineItem({
      eventType: "item.completed",
      itemId: "tool:call-1",
      runId: "req-1",
      seq: 900,
      eventSeq: 7,
      orderKey: 42,
      transient: true,
      payload: {},
    });

    expect(item?.orderKey).toBe(42);
    expect(item?.eventSeq).toBe(7);
    expect(item?.transient).toBe(true);
  });

  it("does not invent a timeline identity without itemId", () => {
    expect(toTimelineItem({
      eventType: "item.upserted",
      eventSeq: 3,
      payload: {},
    })).toBeUndefined();
  });

  it("keeps run-final items distinct from terminal control frames", () => {
    const finalItem = {
      eventType: "run.completed",
      itemId: "run:req-1:final",
      phase: "FINAL",
    };

    expect(toTimelineItem(finalItem)?.itemId).toBe("run:req-1:final");
    expect(isTerminalRunControl(finalItem)).toBe(false);
    expect(isTerminalRunControl({ eventType: "run.completed" })).toBe(true);
    expect(isTerminalRunControl({ eventType: "run.wake" })).toBe(false);
  });

  it("parks a WAITING_INPUT idle control until the run is resumed", () => {
    expect(isWaitingInputIdleControl({
      eventType: "stream.follow_idle",
      status: "waiting_input",
    })).toBe(true);
    expect(isWaitingInputIdleControl({
      eventType: "stream.follow_idle",
      status: "running",
    })).toBe(false);
  });

  it("recognizes a run-final item as the end of the live stream", () => {
    expect(resolveRunFinalItemStatus({
      eventType: "item.completed",
      itemId: "run:req-1:final",
      kind: "ASSISTANT_MESSAGE",
      phase: "FINAL",
      itemStatus: "COMPLETED",
    } as AgentTimelineItem)).toBe("SUCCESS");
    expect(resolveRunFinalItemStatus({
      eventType: "item.completed",
      itemId: "run:req-2:final",
      kind: "ASSISTANT_MESSAGE",
      phase: "FINAL",
      itemStatus: "FAILED",
    } as AgentTimelineItem)).toBe("FAILED");
    expect(resolveRunFinalItemStatus({
      eventType: "item.completed",
      itemId: "tool:call-1",
      kind: "TOOL_CALL",
      phase: "INTERMEDIATE",
      itemStatus: "COMPLETED",
    } as AgentTimelineItem)).toBe("");
  });

  it("only opens timeline streams for non-terminal runs", () => {
    expect(shouldObserveRunTimeline("req-1", "RUNNING")).toBe(true);
    expect(shouldObserveRunTimeline("req-1", "success")).toBe(false);
    expect(shouldObserveRunTimeline("req-1", "FAILED")).toBe(false);
    expect(shouldObserveRunTimeline("req-1", "STOPPED")).toBe(false);
    expect(shouldObserveRunTimeline("req-1", "TIMEOUT")).toBe(false);
    expect(shouldObserveRunTimeline("req-1", "WAITING_INPUT")).toBe(false);
    expect(shouldObserveRunTimeline("", "RUNNING")).toBe(false);
  });

  it("keeps a live timeline open until its final item arrives", () => {
    expect(shouldObserveRunTimeline("req-1", "SUCCESS", true)).toBe(true);
    expect(shouldObserveRunTimeline("req-1", "FAILED", true)).toBe(true);
    expect(shouldObserveRunTimeline("req-1", "WAITING_INPUT", true)).toBe(false);
    expect(shouldObserveRunTimeline("req-1", "SUCCESS", false)).toBe(false);
  });
});
