import { describe, expect, it, vi } from "vitest";

import { runCleanupSteps } from "./GenUiModel3D";

describe("GenUiModel3D cleanup", () => {
  it("runs all registered partial-resource cleanups even when one throws", () => {
    const order: string[] = [];
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const steps = [
      () => order.push("renderer"),
      () => order.push("environment"),
      () => {
        order.push("listeners");
        throw new Error("dispose failed");
      },
      () => order.push("model"),
    ];

    expect(() => runCleanupSteps(steps)).not.toThrow();
    expect(order).toEqual(["model", "listeners", "environment", "renderer"]);
    expect(consoleError).toHaveBeenCalledTimes(1);
    expect(steps).toEqual([]);

    consoleError.mockRestore();
  });
});
