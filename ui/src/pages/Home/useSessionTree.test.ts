import { describe, expect, it } from "vitest";

import {
  extractSessionTreeDescendants,
  toggleExpandedSessionId,
} from "./useSessionTree";

const session = (
  sessionId: string,
  parentSessionId: string | null,
  sessionKind = parentSessionId ? "subagent" : "conversation"
) => ({
  sessionId,
  title: sessionId,
  status: "SUCCESS",
  sessionKind,
  parentSessionId,
  rootSessionId: parentSessionId ? "root" : sessionId,
  latestQueryText: "",
  runCount: 0,
  finishedRunCount: 0,
  failedRunCount: 0,
  startedAt: "2026-01-01T00:00:00",
  lastActiveAt: "2026-01-01T00:00:00",
});

describe("toggleExpandedSessionId", () => {
  it("基于同步更新的状态判断快速连续点击应展开还是收起", () => {
    const first = toggleExpandedSessionId(new Set(), "session-1");
    const second = toggleExpandedSessionId(first.next, "session-1");

    expect(first.expanding).toBe(true);
    expect(first.next.has("session-1")).toBe(true);
    expect(second.expanding).toBe(false);
    expect(second.next.has("session-1")).toBe(false);
  });

  it("从树接口结果中排除根节点并保留全部后代", () => {
    const rows = extractSessionTreeDescendants(
      [
        session("root", null),
        session("child", "root"),
        session("grandchild", "child"),
      ],
      "root"
    );

    expect(rows.map((row) => [row.session.sessionId, row.depth])).toEqual([
      ["child", 1],
      ["grandchild", 2],
    ]);
  });
});
