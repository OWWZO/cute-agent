import { afterEach, describe, expect, it, vi, beforeEach } from "vitest";
import {
  normalizeAction,
  dispatchGenUiAction,
  registerGenUiActionAdapters,
  resetGenUiActionAdapters,
} from "./genUiActionBus";

describe("genUiActionBus", () => {
  beforeEach(() => {
    resetGenUiActionAdapters();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("does not treat bare actionId as send_message", () => {
    expect(normalizeAction("click me")).toBeNull();
    expect(normalizeAction(undefined, { actionId: "hello" })).toBeNull();
  });

  it("normalizes explicit send_message only", () => {
    expect(
      normalizeAction({ type: "send_message", payload: { content: "hi" } })
    ).toEqual({ type: "send_message", payload: { content: "hi" } });
  });

  it("normalizes patch_ui", () => {
    const action = normalizeAction({
      type: "patch_ui",
      payload: {
        patches: [{ op: "replace", path: "/root/props/title", value: "X" }],
      },
    });
    expect(action?.type).toBe("patch_ui");
  });

  it("dispatches patch_ui to adapter, not sendMessage", async () => {
    const patchUi = vi.fn();
    const sendMessage = vi.fn();
    registerGenUiActionAdapters({ patchUi, sendMessage });
    const result = await dispatchGenUiAction({
      type: "patch_ui",
      payload: {
        patches: [{ op: "replace", path: "/root/props/title", value: "Y" }],
      },
    });
    expect(result).toEqual({ ok: true, type: "patch_ui" });
    expect(patchUi).toHaveBeenCalledTimes(1);
    expect(sendMessage).not.toHaveBeenCalled();
  });

  it("logs adapter failures and keeps the error result", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const failure = new Error("boom");
    registerGenUiActionAdapters({
      sendMessage: () => {
        throw failure;
      },
    });

    const result = await dispatchGenUiAction({
      type: "send_message",
      payload: { content: "hi" },
    });

    expect(result).toMatchObject({
      ok: false,
      type: "send_message",
      reason: "error",
      error: failure,
    });
    expect(consoleError).toHaveBeenCalledTimes(1);
  });

  it("returns invalid when action cannot be normalized", async () => {
    const result = await dispatchGenUiAction("bare-string");
    expect(result).toEqual({ ok: false, reason: "invalid" });
  });
});
