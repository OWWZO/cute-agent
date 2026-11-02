import { describe, expect, it } from "vitest";
import {
  resolveAgentPhaseLabel,
  resolveAgentPhaseTone,
} from "./agentProgressGroups";

describe("agent phase helpers", () => {
  it("maps phase labels", () => {
    expect(resolveAgentPhaseLabel("working", "running")).toBe("");
    expect(resolveAgentPhaseLabel(undefined, "failed")).toBe("Failed");
    expect(resolveAgentPhaseLabel("completed", "completed")).toBe("Completed");
  });

  it("maps phase tones from phase/status rather than display labels", () => {
    expect(resolveAgentPhaseTone("working", "running")).toBe("running");
    expect(resolveAgentPhaseTone("queued", "running")).toBe("running");
    expect(resolveAgentPhaseTone("suspended", "running")).toBe("neutral");
    expect(resolveAgentPhaseTone(undefined, "failed")).toBe("error");
    expect(resolveAgentPhaseTone("completed", "completed")).toBe("ok");
  });
});
