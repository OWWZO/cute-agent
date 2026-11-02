import { describe, expect, it } from "vitest";

import { resolveVisibleRowRange } from "./SimpleTable";

describe("resolveVisibleRowRange", () => {
  it("小表全量渲染", () => {
    expect(resolveVisibleRowRange(30, 0, 420)).toEqual({ start: 0, end: 30 });
  });

  it("大表只保留视口附近的行并带上下缓冲", () => {
    expect(resolveVisibleRowRange(1000, 0, 420)).toEqual({ start: 0, end: 25 });
    expect(resolveVisibleRowRange(1000, 280, 420)).toEqual({ start: 0, end: 35 });
    expect(resolveVisibleRowRange(1000, 9800, 420)).toEqual({
      start: 340,
      end: 375,
    });
  });
});
