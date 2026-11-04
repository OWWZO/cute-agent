import { describe, expect, it } from "vitest";

import { shouldCancelPickerOnDisable } from "./useHtmlElementPicker";

describe("useHtmlElementPicker", () => {
  it("选择器失去启用条件时请求取消活动会话", () => {
    expect(shouldCancelPickerOnDisable(false, true)).toBe(true);
    expect(shouldCancelPickerOnDisable(true, true)).toBe(false);
    expect(shouldCancelPickerOnDisable(false, false)).toBe(false);
  });
});
