import { describe, expect, it } from "vitest";

import {
  pruneTabHistory,
  pushTabHistory,
  takeTabBack,
} from "./workspaceTabHistory";

describe("workspaceTabHistory", () => {
  it("只在真正切换 tab 时记录上一个前台", () => {
    expect(pushTabHistory([], "", "A")).toEqual([]);
    expect(pushTabHistory([], "A", "A")).toEqual([]);
    expect(pushTabHistory([], "A", "B")).toEqual(["A"]);
  });

  it("连续返回按浏览器历史顺序回退", () => {
    const stack = pushTabHistory(pushTabHistory([], "A", "B"), "B", "C");
    expect(stack).toEqual(["A", "B"]);

    const first = takeTabBack(stack, new Set(["A", "B", "C"]), "C");
    expect(first).toEqual({ stack: ["A"], target: "B" });

    const second = takeTabBack(
      first.stack,
      new Set(["A", "B", "C"]),
      first.target
    );
    expect(second).toEqual({ stack: [], target: "A" });
  });

  it("返回时跳过已关闭和当前 tab", () => {
    expect(
      takeTabBack(["A", "B", "C"], new Set(["A", "C"]), "C")
    ).toEqual({ stack: [], target: "A" });
    expect(
      takeTabBack(["A"], new Set(["A"]), "A")
    ).toEqual({ stack: [], target: "" });
  });

  it("清理已关闭 tab 的历史项", () => {
    const stack = ["A", "B", "C"];
    expect(pruneTabHistory(stack, new Set(["A", "C"]))).toEqual(["A", "C"]);
    expect(pruneTabHistory(stack, new Set(["A", "B", "C"]))).toBe(stack);
  });
});
