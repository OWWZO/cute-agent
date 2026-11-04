import { describe, expect, it } from "vitest";

import { hasApprovablePlan } from "./PlanComposerBar";

describe("PlanComposerBar approval guard", () => {
  it("requires the edited plan itself to contain non-whitespace content", () => {
    expect(hasApprovablePlan("## Plan")).toBe(true);
    expect(hasApprovablePlan("")).toBe(false);
    expect(hasApprovablePlan("   \n\t")).toBe(false);
  });
});
