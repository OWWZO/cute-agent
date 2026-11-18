import { beforeEach, describe, expect, it } from "vitest";

import {
  clearWorkspaceTabScroll,
  readWorkspaceTabScroll,
  resetWorkspaceTabScrollCache,
  writeWorkspaceTabScroll,
} from "./workspaceTabScrollCache";

const snapshot = {
  scrollTop: 100,
  scrollHeight: 1000,
  clientHeight: 300,
  ratio: 0.15,
  anchorId: "tool-1",
  anchorOffset: 12,
  atBottom: false,
};

describe("workspaceTabScrollCache", () => {
  beforeEach(() => {
    resetWorkspaceTabScrollCache();
  });

  it("按工作区与标签页隔离快照", () => {
    writeWorkspaceTabScroll("session-a", "agent:1", snapshot);
    writeWorkspaceTabScroll("session-a", "agent:2", { ...snapshot, scrollTop: 200 });
    writeWorkspaceTabScroll("session-b", "agent:1", { ...snapshot, scrollTop: 300 });

    expect(readWorkspaceTabScroll("session-a", "agent:1")?.scrollTop).toBe(100);
    expect(readWorkspaceTabScroll("session-a", "agent:2")?.scrollTop).toBe(200);
    expect(readWorkspaceTabScroll("session-b", "agent:1")?.scrollTop).toBe(300);
  });

  it("可删除单个标签页或整个工作区", () => {
    writeWorkspaceTabScroll("session-a", "agent:1", snapshot);
    writeWorkspaceTabScroll("session-a", "agent:2", snapshot);

    clearWorkspaceTabScroll("session-a", "agent:1");
    expect(readWorkspaceTabScroll("session-a", "agent:1")).toBeUndefined();
    expect(readWorkspaceTabScroll("session-a", "agent:2")).toBeDefined();

    clearWorkspaceTabScroll("session-a");
    expect(readWorkspaceTabScroll("session-a", "agent:2")).toBeUndefined();
  });

  it("新快照覆盖旧快照", () => {
    writeWorkspaceTabScroll("session-a", "agent:1", snapshot);
    writeWorkspaceTabScroll("session-a", "agent:1", {
      ...snapshot,
      scrollTop: 888,
      anchorId: "tool-9",
    });
    expect(readWorkspaceTabScroll("session-a", "agent:1")).toMatchObject({
      scrollTop: 888,
      anchorId: "tool-9",
    });
  });

  it("缺少 key 时不写入也不读取", () => {
    writeWorkspaceTabScroll(undefined, "agent:1", snapshot);
    writeWorkspaceTabScroll("session-a", undefined, snapshot);
    expect(readWorkspaceTabScroll(undefined, "agent:1")).toBeUndefined();
    expect(readWorkspaceTabScroll("session-a", undefined)).toBeUndefined();
  });
});
