export const AGENT_INGRESS_VERSION_HEADER = "X-Agent-Ingress-Version";
export const AGENT_INGRESS_VERSION = "1";

export type AgentIngressMode =
  | "new_run"
  | "injected"
  | "queued"
  | "held"
  | "inject_failed";

export type AgentIngressResponseFields = {
  mode?: AgentIngressMode;
  activeRequestId?: string | null;
  retryable?: boolean;
  duplicate?: boolean;
};
