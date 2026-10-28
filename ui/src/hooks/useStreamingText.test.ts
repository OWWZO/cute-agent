import { describe, expect, it } from "vitest";
import { resolveStreamingChunkSize } from "./useStreamingText";

describe("resolveStreamingChunkSize", () => {
  it("never returns less than one character", () => {
    expect(resolveStreamingChunkSize(0)).toBe(1);
    expect(resolveStreamingChunkSize(6)).toBe(1);
  });

  it("scales with backlog", () => {
    expect(resolveStreamingChunkSize(7)).toBe(2);
    expect(resolveStreamingChunkSize(60)).toBe(10);
  });

  it("caps at the per-frame maximum", () => {
    expect(resolveStreamingChunkSize(240)).toBe(24);
    expect(resolveStreamingChunkSize(9999)).toBe(24);
  });

  it("is monotonically non-decreasing", () => {
    let previous = 0;
    for (let ready = 0; ready <= 400; ready += 1) {
      const chunk = resolveStreamingChunkSize(ready);
      expect(chunk).toBeGreaterThanOrEqual(previous);
      previous = chunk;
    }
  });
});
