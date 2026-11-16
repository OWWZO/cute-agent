import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import AskUserQuestionCard, {
  bypassesAskUserEnterShortcut,
} from "./AskUserQuestionCard";
import DesktopControlCard from "./DesktopControlCard";

describe("AskUserQuestionCard keyboard handling", () => {
  it("leaves Enter to native buttons, links, selects and text fields", () => {
    for (const tagName of ["BUTTON", "A", "SELECT", "INPUT", "TEXTAREA"]) {
      expect(bypassesAskUserEnterShortcut(tagName)).toBe(true);
    }
    expect(bypassesAskUserEnterShortcut("DIV")).toBe(false);
  });
});

describe("AskUserQuestionCard visual language", () => {
  it("uses Lucide icons instead of text or emoji glyphs", () => {
    const html = renderToStaticMarkup(
      createElement(AskUserQuestionCard, {
        tool: {
          id: "ask-1",
          messageId: "ask-1",
          messageType: "ask_user_question",
          resultMap: {
            status: "pending",
            questions: [
              {
                question: "选择方案",
                options: [{ label: "方案 A" }, { label: "方案 B" }],
              },
            ],
          },
        } as unknown as CHAT.Task,
      })
    );

    expect(html).toContain("lucide-circle-question-mark");
    expect(html).toContain("lucide-circle");
    expect(html).not.toContain(">?</span>");
  });

  it("renders desktop control with a Lucide monitor icon", () => {
    const html = renderToStaticMarkup(
      createElement(DesktopControlCard, {
        tool: {
          id: "desktop-1",
          messageId: "desktop-1",
          messageType: "desktop_control",
          resultMap: {
            status: "pending",
            controlId: "control-1",
          },
        } as unknown as CHAT.Task,
      })
    );

    expect(html).toContain("lucide-monitor");
    expect(html).not.toContain("🖥");
  });
});
