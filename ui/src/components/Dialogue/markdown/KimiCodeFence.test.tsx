import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import gfm from "remark-gfm";
import {
  DiffCodeFence,
  KimiCodeFence,
  parseDiffLines,
} from "./KimiCodeFence";
import { shouldTokenizeCode } from "./codeHighlightPolicy";

describe("parseDiffLines", () => {
  it("classifies add/del/hunk/ctx", () => {
    const rows = parseDiffLines(
      "@@ -1,2 +1,2 @@\n-old line\n+new line\n context"
    );
    expect(rows.map((r) => r.type)).toEqual([
      "hunk",
      "del",
      "add",
      "ctx",
    ]);
    expect(rows[1]).toEqual({ type: "del", sign: "-", text: "old line" });
    expect(rows[2]).toEqual({ type: "add", sign: "+", text: "new line" });
  });
});

describe("DiffCodeFence", () => {
  it("流式期间按纯文本展示，不构造逐行 diff DOM", () => {
    const html = renderToStaticMarkup(
      <DiffCodeFence code={"@@ -1 +1 @@\n-old\n+new"} streaming />
    );

    expect(html).toContain("kimi-code-fence-fallback");
    expect(html).not.toContain("kimi-diff-line");
  });

  it("落定后展示完整 diff 行", () => {
    const html = renderToStaticMarkup(
      <DiffCodeFence code={"@@ -1 +1 @@\n-old\n+new"} />
    );

    expect(html).toContain("kimi-diff-line");
    expect(html).toContain("kimi-diff-add");
    expect(html).toContain("kimi-diff-del");
  });
});

describe("markdown fence structure", () => {
  it("pre unwrap yields a single kimi fence shell without nested markdown pre", () => {
    const Pre = ({ children }: { children?: ReactNode }) => <>{children}</>;
    const Code = ({
      className,
      children,
    }: {
      className?: string;
      children?: ReactNode;
    }) => {
      const match = /language-(\w+)/.exec(className || "");
      const text = String(children ?? "").replace(/\n$/, "");
      if (match) {
        return <KimiCodeFence code={text} language={match[1]} />;
      }
      return <code>{children}</code>;
    };
    const html = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[gfm]} components={{ pre: Pre, code: Code }}>
        {"```python\nprint(1)\n```"}
      </ReactMarkdown>
    );
    expect(html).toContain("kimi-code-fence");
    expect(html).toContain("python");
    expect(html.indexOf("<pre")).toBe(html.lastIndexOf("<pre"));
    expect(html).not.toContain('class="shiki');
  });
});

describe("shouldTokenizeCode", () => {
  // 回归：流式期间必须跳过 shiki。`code` 每帧都在变，tokenize 是异步的，
  // 会和消息流更新叠加 —— ZCode 明确在流式时关掉高亮。
  it("skips tokenizing while streaming", () => {
    expect(shouldTokenizeCode("const a = 1;", true)).toBe(false);
  });

  it("tokenizes once the message settles", () => {
    expect(shouldTokenizeCode("const a = 1;", false)).toBe(true);
  });

  it("never tokenizes upstream shiki HTML", () => {
    expect(shouldTokenizeCode('<pre class="shiki">x</pre>', false)).toBe(false);
  });
});
