import { describe, expect, it } from "vitest";

import { parseAgentAnswer } from "./sseParsers";

function eventData(): MESSAGE.EventData {
  return {
    taskId: "task-1",
    taskOrder: 1,
    messageOrder: 1,
    messageType: "task",
    messageId: "msg-1",
    resultMap: {
      requestId: "req-1",
      messageId: "msg-1",
      messageType: "agent_stream",
      result: "正文",
    } as MESSAGE.Task,
  };
}

describe("parseAgentAnswer timeline envelope", () => {
  it("normalizes item events to legacy task frames", () => {
    const parsed = parseAgentAnswer({
      eventType: "item.delta",
      payload: { eventData: eventData() },
    });

    expect(parsed.packageType).toBe("task");
    expect(parsed.resultMap.eventData).toEqual(eventData());
  });

  it("normalizes run.failed to the existing result frame", () => {
    const parsed = parseAgentAnswer({
      eventType: "run.failed",
      status: "failed",
      finished: true,
      errorMsg: "失败",
    });

    expect(parsed.packageType).toBe("result");
    expect(parsed.status).toBe("failed");
    expect(parsed.finished).toBe(true);
  });

  it("keeps the explicit transport finished flag on stream.closed", () => {
    const parsed = parseAgentAnswer({
      eventType: "stream.closed",
      status: "success",
      finished: true,
    });

    expect(parsed.packageType).toBe("stream_settle");
    expect(parsed.finished).toBe(true);
  });
});
