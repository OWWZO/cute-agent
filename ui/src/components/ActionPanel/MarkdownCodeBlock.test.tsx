import { describe, expect, it } from "vitest";
import type { FC } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createCodeBlock } from "./MarkdownCodeBlock";
import MarkdownRenderer from "./MarkdownRenderer";

type CodeBlockProps = {
  inline?: boolean;
  className?: string;
  children?: unknown;
  "data-block"?: string;
};

function renderCodeBlock(props: CodeBlockProps) {
  const CodeBlock = createCodeBlock(false) as FC<CodeBlockProps>;
  return renderToStaticMarkup(<CodeBlock {...props} />);
}

describe("MarkdownCodeBlock", () => {
  it("单行无语言围栏仍渲染代码块，而不是行内 chip", () => {
    const html = renderCodeBlock({
      "data-block": "",
      children: "npm install\n",
    });

    expect(html).toContain("kimi-code-fence");
    expect(html).not.toContain("kimi-inline-code");
  });

  it("行内代码保持 inline chip", () => {
    const html = renderCodeBlock({ children: "npm install" });

    expect(html).toContain("kimi-inline-code");
    expect(html).not.toContain("kimi-code-fence");
  });

  it("空围栏不崩溃", () => {
    expect(() =>
      renderCodeBlock({
        "data-block": "",
        children: "",
      })
    ).not.toThrow();
  });

  it("react-markdown 的单行无语言围栏也走代码块", () => {
    const html = renderToStaticMarkup(
      <MarkdownRenderer markDownContent={"```\nnpm install\n```"} />
    );

    expect(html).toContain("kimi-code-fence");
  });
});
