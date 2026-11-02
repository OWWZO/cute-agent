import { describe, expect, it } from "vitest";

import { resolveMarkdownContent } from "./panelContent";

describe("resolveMarkdownContent", () => {
  const buildTask = (overrides?: Partial<CHAT.Task>): CHAT.Task =>
    ({
      taskId: "task-1",
      messageTime: "1710000000000",
      requestId: "req-1",
      messageId: "msg-1",
      finish: true,
      isFinal: true,
      id: "task-1",
      messageType: "code",
      resultMap: {},
      ...overrides,
    }) as CHAT.Task;

  it("只有 code 时用无语言标记的代码块，避免非 Python 内容被误高亮", () => {
    const markdown = resolveMarkdownContent(
      buildTask({ resultMap: { code: "console.log(1)" } as CHAT.Task["resultMap"] })
    );

    expect(markdown).toBe("```\nconsole.log(1)\n```");
  });

  it("llm_reasoning 在顶层缺失时回退到 resultMap.reasoningContent", () => {
    const markdown = resolveMarkdownContent(
      buildTask({
        messageType: "llm_reasoning",
        resultMap: { reasoningContent: "先看日志" } as CHAT.Task["resultMap"],
      })
    );

    expect(markdown).toBe("先看日志");
  });
});
