import { describe, expect, it } from "vitest";

import { isPlanSolveConversation } from "./agentMode";

describe("agentMode", () => {
  it("识别 PlanSolve 枚举值", () => {
    expect(isPlanSolveConversation(3, "normal")).toBe(true);
    expect(isPlanSolveConversation(5, "normal")).toBe(false);
    expect(isPlanSolveConversation(undefined, "cluster")).toBe(true);
  });
});
