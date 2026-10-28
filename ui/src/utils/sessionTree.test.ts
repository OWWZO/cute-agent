import { describe, expect, it } from "vitest";

import type { ConversationSessionItem } from "@/services/agentConversation";
import { buildSessionTree, flattenSessionTree } from "./sessionTree";

function session(
  sessionId: string,
  patch: Partial<ConversationSessionItem> = {}
): ConversationSessionItem {
  return {
    sessionId,
    title: sessionId,
    status: "SUCCESS",
    latestQueryText: "",
    runCount: 1,
    finishedRunCount: 1,
    failedRunCount: 0,
    startedAt: "",
    lastActiveAt: "",
    ...patch,
  };
}

describe("buildSessionTree", () => {
  it("主会话在下挂两个子会话", () => {
    const { roots, orphans } = buildSessionTree([
      session("main", { sessionKind: "conversation" }),
      session("sub_a", {
        sessionKind: "subagent",
        parentSessionId: "main"
      }),
      session("sub_b", {
        sessionKind: "subagent",
        parentSessionId: "main"
      }),
    ]);

    expect(orphans).toHaveLength(0);
    expect(roots).toHaveLength(1);
    expect(roots[0].session.sessionId).toBe("main");
    expect(roots[0].children.map((node) => node.session.sessionId)).toEqual([
      "sub_a",
      "sub_b",
    ]);
    expect(roots[0].children[0].depth).toBe(1);
  });

  it("支持多层嵌套", () => {
    const { roots } = buildSessionTree([
      session("main", { sessionKind: "conversation" }),
      session("sub_a", {
        sessionKind: "subagent",
        parentSessionId: "main"
      }),
      session("sub_a1", {
        sessionKind: "subagent",
        parentSessionId: "sub_a"
      }),
    ]);

    expect(roots[0].children[0].children[0].session.sessionId).toBe("sub_a1");
    expect(roots[0].children[0].children[0].depth).toBe(2);
  });

  it("父会话不在列表里的节点进孤儿，不静默丢弃", () => {
    const { roots, orphans } = buildSessionTree([
      session("main", { sessionKind: "conversation" }),
      session("sub_lost", {
        sessionKind: "subagent",
        parentSessionId: "gone"
      }),
    ]);

    expect(roots.map((node) => node.session.sessionId)).toEqual(["main"]);
    expect(orphans.map((node) => node.session.sessionId)).toEqual(["sub_lost"]);
  });

  it("子会话没有父 ID 时不混进主会话列表", () => {
    const { roots, orphans } = buildSessionTree([
      session("main", { sessionKind: "conversation" }),
      session("sub_no_parent", { sessionKind: "subagent" }),
    ]);

    expect(roots.map((node) => node.session.sessionId)).toEqual(["main"]);
    expect(orphans.map((node) => node.session.sessionId)).toEqual(["sub_no_parent"]);
  });

  it("血缘成环不会无限递归", () => {
    const { roots } = buildSessionTree([
      session("a", {
        sessionKind: "subagent",
        parentSessionId: "b"
      }),
      session("b", {
        sessionKind: "subagent",
        parentSessionId: "a"
      }),
    ]);

    // 两个都互为父：没有任何根，也不会爆栈
    expect(roots).toHaveLength(0);
  });
});

describe("flattenSessionTree", () => {
  it("深度优先展开，父在子前", () => {
    const { roots } = buildSessionTree([
      session("main", { sessionKind: "conversation" }),
      session("sub_a", {
        sessionKind: "subagent",
        parentSessionId: "main"
      }),
      session("sub_a1", {
        sessionKind: "subagent",
        parentSessionId: "sub_a"
      }),
      session("sub_b", {
        sessionKind: "subagent",
        parentSessionId: "main"
      }),
    ]);

    expect(flattenSessionTree(roots).map((node) => node.session.sessionId)).toEqual([
      "main",
      "sub_a",
      "sub_a1",
      "sub_b",
    ]);
  });
});
