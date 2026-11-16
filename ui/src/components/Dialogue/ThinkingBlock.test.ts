import { describe, expect, it } from "vitest";
import { resolveStreamingSummary } from "./ThinkingBlock";

function splitParagraphs(text: string): string[] {
  return text
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
}

describe("resolveStreamingSummary", () => {
  it("只返回最后一个已经结束的非空行", () => {
    expect(resolveStreamingSummary("第一行\n第二行\n  \n最后一行  ")).toBe(
      "第二行"
    );
    expect(resolveStreamingSummary("空\n\n")).toBe("空");
    expect(resolveStreamingSummary("\n  \n")).toBeUndefined();
    expect(resolveStreamingSummary("超长".repeat(1000))).toBeUndefined();
  });
});

describe("thinking paragraph fold", () => {
  it("uses last paragraph as teaser", () => {
    const parts = splitParagraphs("first para\n\nsecond para\n\nfinal teaser");
    expect(parts[parts.length - 1]).toBe("final teaser");
    expect(parts.length).toBe(3);
  });

  it("single paragraph still provides a teaser candidate", () => {
    expect(splitParagraphs("only one")).toEqual(["only one"]);
  });
});
