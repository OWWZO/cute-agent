import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("ahooks", () => ({
  useRequest: () => ({
    data: "# 深度研究报告\n\n正文内容",
    loading: false,
    error: undefined,
  }),
}));

import MarkdownFileRenderer from "./MarkdownFileRenderer";

describe("MarkdownFileRenderer", () => {
  it("Markdown 预览复用聊天终答的 Markdown 排版入口", () => {
    const html = renderToStaticMarkup(
      <MarkdownFileRenderer
        fileUrl="https://example.com/report.md"
        fileName="研究报告.md"
        hideChrome
      />
    );

    expect(html).toContain("chat-dialogue");
    expect(html).toContain("markdown-body");
    expect(html).toContain("chat-markdown");
    expect(html).toContain("conclusion-markdown");
    expect(html).toContain("kimi-md");
    expect(html).toContain("<h1>深度研究报告</h1>");
    expect(html).toContain("正文内容");
  });
});
