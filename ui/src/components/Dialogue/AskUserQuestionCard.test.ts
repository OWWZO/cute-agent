import { describe, expect, it } from "vitest";

import { bypassesAskUserEnterShortcut } from "./AskUserQuestionCard";

describe("AskUserQuestionCard keyboard handling", () => {
  it("leaves Enter to native buttons, links, selects and text fields", () => {
    for (const tagName of ["BUTTON", "A", "SELECT", "INPUT", "TEXTAREA"]) {
      expect(bypassesAskUserEnterShortcut(tagName)).toBe(true);
    }
    expect(bypassesAskUserEnterShortcut("DIV")).toBe(false);
  });
});
