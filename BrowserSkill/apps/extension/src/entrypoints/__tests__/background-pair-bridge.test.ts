import { afterEach, describe, expect, it, vi } from "vitest";

// `background.ts` calls the WXT-injected global at module load; vitest runs the
// file directly, so stub it before the import is resolved.
vi.hoisted(() => {
  (globalThis as unknown as { defineBackground: (cb: unknown) => unknown }).defineBackground = (
    cb: unknown,
  ) => cb;
});

import { injectPairBridgeIntoOpenTabs } from "@/entrypoints/background";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("injectPairBridgeIntoOpenTabs", () => {
  it("injects the pairing bridge into matching tabs that have an id", async () => {
    const executeScript = vi.fn().mockResolvedValue([]);
    vi.stubGlobal("chrome", {
      tabs: { query: vi.fn().mockResolvedValue([{ id: 1 }, { id: 2 }, {}]) },
      scripting: { executeScript },
    });

    await injectPairBridgeIntoOpenTabs();

    expect(executeScript).toHaveBeenCalledTimes(2);
    expect(executeScript).toHaveBeenNthCalledWith(1, {
      target: { tabId: 1 },
      files: ["content-scripts/bsk-pair.js"],
    });
    expect(executeScript).toHaveBeenNthCalledWith(2, {
      target: { tabId: 2 },
      files: ["content-scripts/bsk-pair.js"],
    });
  });

  it("keeps injecting the remaining tabs when one page rejects injection", async () => {
    const executeScript = vi
      .fn()
      .mockRejectedValueOnce(new Error("restricted page"))
      .mockResolvedValueOnce([]);
    vi.stubGlobal("chrome", {
      tabs: { query: vi.fn().mockResolvedValue([{ id: 1 }, { id: 2 }]) },
      scripting: { executeScript },
    });

    await expect(injectPairBridgeIntoOpenTabs()).resolves.toBeUndefined();

    expect(executeScript).toHaveBeenCalledTimes(2);
  });

  it("does nothing when the scripting API is unavailable", async () => {
    const query = vi.fn();
    vi.stubGlobal("chrome", { tabs: { query } });

    await expect(injectPairBridgeIntoOpenTabs()).resolves.toBeUndefined();

    expect(query).not.toHaveBeenCalled();
  });
});
