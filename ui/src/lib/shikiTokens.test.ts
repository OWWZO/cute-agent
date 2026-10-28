import { afterEach, describe, expect, it } from "vitest";
import type { BundledLanguage } from "shiki";
import {
  resetShikiTokenCache,
  shouldUseSyntaxHighlighting,
  tokenizeCode,
} from "./shikiTokens";

afterEach(() => {
  resetShikiTokenCache();
});

describe("shouldUseSyntaxHighlighting", () => {
  // 纯文本语言没有高亮收益，却会让每次渲染都进一遍 shiki 状态机。
  // 对齐 ZCode `shikiHighlighter.ts` 的 PLAIN_TEXT_CODE_LANGUAGES 短路。
  it("skips plain text languages", () => {
    for (const language of ["text", "txt", "plain", "log", "output", ""]) {
      expect(shouldUseSyntaxHighlighting(language)).toBe(false);
    }
  });

  it("keeps real languages highlighted", () => {
    for (const language of ["html", "typescript", "python"]) {
      expect(shouldUseSyntaxHighlighting(language)).toBe(true);
    }
  });

  it("ignores surrounding whitespace and case", () => {
    expect(shouldUseSyntaxHighlighting("  TEXT  ")).toBe(false);
  });
});

describe("tokenizeCode", () => {
  // 纯文本走 raw tokens：不加载 shiki，也不依赖异步。
  it("returns raw tokens for plain text without touching shiki", async () => {
    const result = await tokenizeCode("a\n\nb", "text" as BundledLanguage, false);
    expect(result.tokens).toHaveLength(3);
    expect(result.tokens[0]).toEqual([{
      color: "inherit",
      content: "a"
    }]);
    // 空行保留为空数组，与 shiki 的输出形状一致
    expect(result.tokens[1]).toEqual([]);
    expect(result.tokens[2]).toEqual([{
      color: "inherit",
      content: "b"
    }]);
  });

  // 纯文本同样要缓存：日志 / 输出块经常整段重渲染，不该每次都重建 token 数组。
  it("returns the identical cached object for repeated plain text", async () => {
    const code = "line one\nline two";
    const first = await tokenizeCode(code, "text" as BundledLanguage, false);
    const second = await tokenizeCode(code, "text" as BundledLanguage, false);
    expect(second).toBe(first);
  });

  it("rebuilds raw tokens when the content changes", async () => {
    const first = await tokenizeCode("alpha", "text" as BundledLanguage, false);
    const second = await tokenizeCode("beta", "text" as BundledLanguage, false);
    expect(second).not.toBe(first);
    expect(second.tokens).toEqual([[
      {
        color: "inherit",
        content: "beta"
      }
    ]]);
  });

  // 回归：同一份代码重复高亮必须命中缓存。30KB HTML 单次 tokenize 实测 ~116ms，
  // 没有缓存时每次渲染都是这个量级，是「展开后整个前端卡住」的主因。
  it("returns the identical cached object for repeated calls", async () => {
    const code = "<div>hi</div>";
    const first = await tokenizeCode(code, "html" as BundledLanguage, false);
    const second = await tokenizeCode(code, "html" as BundledLanguage, false);
    expect(second).toBe(first);
  });

  // 并发去重：多个组件同时请求同一段代码时共用一个 in-flight promise。
  it("deduplicates concurrent requests for the same code", async () => {
    const code = "<span>same</span>";
    const [left, right] = await Promise.all([
      tokenizeCode(code, "html" as BundledLanguage, false),
      tokenizeCode(code, "html" as BundledLanguage, false),
    ]);
    expect(right).toBe(left);
  });

  // 主题是缓存 key 的一部分：换主题必须重新 tokenize，不能复用旧配色。
  it("does not reuse tokens across themes", async () => {
    const code = "<b>x</b>";
    const light = await tokenizeCode(code, "html" as BundledLanguage, false);
    const dark = await tokenizeCode(code, "html" as BundledLanguage, true);
    expect(dark).not.toBe(light);
  });

  // 语言是缓存 key 的一部分：同样的文本按不同语法解析结果不同。
  it("does not reuse tokens across languages", async () => {
    const code = "const a = 1";
    const asTs = await tokenizeCode(code, "typescript" as BundledLanguage, false);
    const asPy = await tokenizeCode(code, "python" as BundledLanguage, false);
    expect(asPy).not.toBe(asTs);
  });
});
