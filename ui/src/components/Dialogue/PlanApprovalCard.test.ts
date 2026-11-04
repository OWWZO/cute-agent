import { describe, expect, it } from "vitest";

import {
  buildPlanApprovalApprovePayload,
  resolvePlanApprovalSessionId,
  shouldHandlePlanApprovalShortcut,
} from "./PlanApprovalCard";

describe("PlanApprovalCard shortcuts", () => {
  it("only handles shortcuts when focus belongs to this card", () => {
    const active = {} as Element;
    const container = { contains: (node: Node) => node === active };

    expect(shouldHandlePlanApprovalShortcut(container, active)).toBe(true);
    expect(shouldHandlePlanApprovalShortcut(container, {} as Element)).toBe(false);
    expect(shouldHandlePlanApprovalShortcut(null, active)).toBe(false);
  });

  it("builds an approve payload from the latest edited plan and feedback", () => {
    expect(
      buildPlanApprovalApprovePayload({
        approvalId: "a1",
        planContent: "old",
        editedPlan: "new",
        feedback: "  looks good  ",
      })
    ).toEqual({
      approvalId: "a1",
      editedPlanContent: "new",
      feedback: "looks good",
    });
  });

  it("resolves the current nested session id without stale closure state", () => {
    expect(
      resolvePlanApprovalSessionId(undefined, {
        resultMap: { sessionId: "s-nested" },
      })
    ).toBe("s-nested");
    expect(
      resolvePlanApprovalSessionId("s-response", {
        sessionId: "s-old",
      })
    ).toBe("s-response");
  });
});
