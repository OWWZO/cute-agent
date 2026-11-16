import { describe, expect, it } from "vitest";
import { resolveToolKey } from "./toolTaskAdapter";

function tool(partial: Partial<CHAT.Task>): CHAT.Task {
  return {
    messageType: "tool_call",
    resultMap: {},
    ...partial,
  } as CHAT.Task;
}

describe("resolveToolKey", () => {
  it("子工具共享父 taskId 时仍按各自 toolCallId 隔离展开状态", () => {
    const first = tool({
      taskId: "parent-agent",
      id: "child-1",
      resultMap: { toolCallId: "call-1" },
    });
    const second = tool({
      taskId: "parent-agent",
      id: "child-2",
      resultMap: { toolCallId: "call-2" },
    });

    expect(resolveToolKey(first)).toBe("call-1");
    expect(resolveToolKey(second)).toBe("call-2");
  });
});
