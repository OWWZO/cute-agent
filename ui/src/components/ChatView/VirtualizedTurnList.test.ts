import { describe, expect, it, vi } from "vitest";

import {
  deliverViewerReady,
  virtualRowOffset,
} from "./VirtualizedTurnList";

describe("VirtualizedTurnList geometry", () => {
  it("positions rows relative to a list shifted by the header slot", () => {
    expect(virtualRowOffset(420, 120)).toBe(300);
    expect(virtualRowOffset(120, 0)).toBe(120);
  });
});

describe("VirtualizedTurnList ready callback", () => {
  it("delivers the handle when onReady arrives after mount", () => {
    const scrollToIndex = vi.fn();
    const onReady = vi.fn();

    expect(deliverViewerReady(undefined, scrollToIndex)).toBe(false);
    expect(deliverViewerReady(onReady, scrollToIndex)).toBe(true);
    expect(onReady).toHaveBeenCalledWith({ scrollToIndex });
  });
});
