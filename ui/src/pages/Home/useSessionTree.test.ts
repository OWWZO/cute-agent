import { describe, expect, it } from "vitest";

import { toggleExpandedSessionId } from "./useSessionTree";

describe("toggleExpandedSessionId", () => {
  it("基于同步更新的状态判断快速连续点击应展开还是收起", () => {
    const first = toggleExpandedSessionId(new Set(), "session-1");
    const second = toggleExpandedSessionId(first.next, "session-1");

    expect(first.expanding).toBe(true);
    expect(first.next.has("session-1")).toBe(true);
    expect(second.expanding).toBe(false);
    expect(second.next.has("session-1")).toBe(false);
  });
});
