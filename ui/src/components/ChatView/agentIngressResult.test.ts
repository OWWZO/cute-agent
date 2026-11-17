import { describe, expect, it } from "vitest";
import {
  isAgentGuidanceMode,
  isAgentHeldMode,
  shouldRetryInjectFailed,
} from "./agentIngressResult";

describe("agent ingress result", () => {
  it("只把 injected/queued 当作已有 run 的指导", () => {
    expect(isAgentGuidanceMode("injected")).toBe(true);
    expect(isAgentGuidanceMode("queued")).toBe(true);
    expect(isAgentGuidanceMode("new_run")).toBe(false);
    expect(isAgentGuidanceMode("held")).toBe(false);
    expect(isAgentGuidanceMode(undefined)).toBe(false);
  });

  it("inject_failed 只允许复用 requestId 自动重试一次", () => {
    expect(
      shouldRetryInjectFailed({
        status: 503,
        mode: "inject_failed",
        retryable: true,
        retryCount: 0,
      })
    ).toBe(true);
    expect(
      shouldRetryInjectFailed({
        status: 503,
        mode: "inject_failed",
        retryable: true,
        retryCount: 1,
      })
    ).toBe(false);
  });

  it("held 不会被当作普通成功", () => {
    expect(isAgentHeldMode("held")).toBe(true);
    expect(isAgentHeldMode("queued")).toBe(false);
  });
});
