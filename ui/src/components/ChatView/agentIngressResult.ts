import type { AgentIngressMode } from "@/services/agentIngressProtocol";

export function isAgentGuidanceMode(
  mode?: AgentIngressMode | null
): mode is "injected" | "queued" {
  return mode === "injected" || mode === "queued";
}

export function shouldRetryInjectFailed(input: {
  status?: number | null;
  mode?: AgentIngressMode | null;
  retryable?: boolean;
  retryCount: number;
}): boolean {
  return (
    input.status === 503 &&
    input.mode === "inject_failed" &&
    input.retryable === true &&
    input.retryCount < 1
  );
}

export function isAgentHeldMode(mode?: AgentIngressMode | null): boolean {
  return mode === "held";
}
