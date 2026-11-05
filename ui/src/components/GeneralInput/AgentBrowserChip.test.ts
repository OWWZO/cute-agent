import { describe, expect, it } from "vitest";

import { resolveAgentBrowserLiveViewUrl } from "./agentBrowserUrl";

describe("resolveAgentBrowserLiveViewUrl", () => {
  it("拒绝 javascript: 地址", () => {
    expect(resolveAgentBrowserLiveViewUrl("javascript:alert(1)")).toBeNull();
  });

  it("允许 http(s) 地址", () => {
    expect(
      resolveAgentBrowserLiveViewUrl("https://browser.example.com/live/1")
    ).toBe("https://browser.example.com/live/1");
  });
});
