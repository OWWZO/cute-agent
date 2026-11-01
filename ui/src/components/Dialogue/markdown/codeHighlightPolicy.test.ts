import { describe, expect, it } from "vitest";

import { shouldTokenizeCode } from "./codeHighlightPolicy";

describe("shouldTokenizeCode", () => {
  it("空白围栏不进入 shiki", () => {
    expect(shouldTokenizeCode("", false)).toBe(false);
    expect(shouldTokenizeCode("   ", false)).toBe(false);
    expect(shouldTokenizeCode("\n\t", false)).toBe(false);
  });

  it("流式期间即使有内容也不高亮", () => {
    expect(shouldTokenizeCode("const a = 1;", true)).toBe(false);
  });

  it("普通代码仍交给 shiki", () => {
    expect(shouldTokenizeCode("const a = 1;", false)).toBe(true);
  });
});
