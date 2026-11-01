import { describe, expect, it } from "vitest";
import { buildQuerySummary } from "./querySummary";

describe("buildQuerySummary formula", () => {
  it("将 overwriteSource key 当作字面字符串替换", () => {
    const summary = buildQuerySummary({
      overwriteCalc: "${a.b + (x)}",
      overwriteSource: {
        "a.b": "销售额",
        "(x)": "成本",
      },
    });

    expect(summary.formula).toBe(" 销售额  +  成本 ");
  });

  it("替换值中的正则替换符号按原文保留，空 key 跳过", () => {
    const summary = buildQuerySummary({
      overwriteCalc: "${x}",
      overwriteSource: {
        "": "ignored",
        x: "$& $1",
      },
    });

    expect(summary.formula).toBe(" $& $1 ");
  });
});
