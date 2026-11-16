import { beforeEach, describe, expect, it } from "vitest";
import {
  readWorkspaceTabState,
  rememberWorkspaceTabState,
  resetWorkspaceTabStateCache,
} from "./workspaceTabStateCache";
import type { WorkspaceTab } from "./workspaceFiles";

const tab = {
  kind: "source",
  key: "source:test",
  source: {
    type: "text",
    title: "test",
    content: "hello",
    language: "text",
  },
} as unknown as WorkspaceTab;

beforeEach(() => {
  resetWorkspaceTabStateCache();
});

describe("workspaceTabStateCache", () => {
  it("restores tabs after the workspace panel is remounted", () => {
    rememberWorkspaceTabState("session-1", {
      tabs: [tab],
      selectedKey: "source:test",
      viewMode: "source",
    });

    expect(readWorkspaceTabState("session-1")).toEqual({
      tabs: [tab],
      selectedKey: "source:test",
      viewMode: "source",
    });
  });

  it("clears the cache snapshot when all tabs are closed", () => {
    rememberWorkspaceTabState("session-1", {
      tabs: [tab],
      selectedKey: "source:test",
      viewMode: "preview",
    });
    rememberWorkspaceTabState("session-1", {
      tabs: [],
      selectedKey: "",
      viewMode: "preview",
    });

    expect(readWorkspaceTabState("session-1").tabs).toEqual([]);
  });
});
