import { describe, expect, it } from "vitest";

import { buildHomeViewPath, isSidebarView, readViewFromSearch } from "./sidebarViews";

describe("sidebarViews", () => {
  it("reads a known view from query", () => {
    expect(readViewFromSearch(new URLSearchParams("view=mrag"))).toBe("mrag");
    expect(readViewFromSearch(new URLSearchParams("view=sub-agents"))).toBe("sub-agents");
  });

  it("falls back to chat for missing or unknown view", () => {
    expect(readViewFromSearch(new URLSearchParams(""))).toBe("chat");
    expect(readViewFromSearch(new URLSearchParams("view=nope"))).toBe("chat");
  });

  it("builds chat without query and other views with it", () => {
    expect(buildHomeViewPath("chat")).toBe("/");
    expect(buildHomeViewPath("models")).toBe("/?view=models");
  });

  it("guards unknown view values", () => {
    expect(isSidebarView("tools")).toBe(true);
    expect(isSidebarView("channels")).toBe(true);
    expect(isSidebarView("unknown")).toBe(false);
    expect(isSidebarView(null)).toBe(false);
  });

  it("round-trips the channels view", () => {
    expect(readViewFromSearch(new URLSearchParams("view=channels"))).toBe("channels");
    expect(buildHomeViewPath("channels")).toBe("/?view=channels");
  });
});
