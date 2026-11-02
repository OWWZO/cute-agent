import { describe, expect, it } from "vitest";
import { normalizeRunStatus } from "./runStatus";

describe("normalizeRunStatus", () => {
  it("uppercases known ledger statuses", () => {
    expect(normalizeRunStatus("success")).toBe("SUCCESS");
    expect(normalizeRunStatus(" waiting_input ")).toBe("WAITING_INPUT");
  });

  it("keeps the historical RUNNING default for empty values", () => {
    expect(normalizeRunStatus()).toBe("RUNNING");
    expect(normalizeRunStatus("")).toBe("RUNNING");
  });

  it("maps unenumerated statuses to UNKNOWN", () => {
    expect(normalizeRunStatus("CANCELLED")).toBe("UNKNOWN");
  });
});
