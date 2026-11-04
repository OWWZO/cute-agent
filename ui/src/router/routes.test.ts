import { describe, expect, it } from "vitest";

import { normalizeReturnUrl, ROUTES } from "./routes";

describe("normalizeReturnUrl", () => {
  it("保留普通站内路径、查询参数和 hash", () => {
    expect(normalizeReturnUrl("/workspace/mrag?tab=files#top")).toBe(
      "/workspace/mrag?tab=files#top"
    );
    expect(normalizeReturnUrl("/?q=100%")).toBe("/?q=100%");
  });

  it.each([
    "//evil.example",
    "/\\evil.example",
    "/%2F%2Fevil.example",
    "/%252F%252Fevil.example",
    "/%09/evil.example",
    "/%0A/evil.example",
  ])("拒绝可被 URL 解析器规范化为外站的返回地址：%s", (value) => {
    expect(normalizeReturnUrl(value)).toBe(ROUTES.HOME);
  });
});
