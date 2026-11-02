import { formatSubAgentDuration } from "@/utils/chat/subagent";
import type { AgentPhase } from "@/types/agentRuntime";

type AgentPhaseState = "running" | "queued" | "suspended" | "completed" | "failed";

/** 把后端 phase / status 归一成展示态；标签与色调都从这同一份状态派生。 */
function resolveAgentPhaseState(
  phase: AgentPhase | string | undefined,
  status: string
): AgentPhaseState {
  switch ((phase || "").toLowerCase()) {
    case "queued":
      return "queued";
    case "working":
      return "running";
    case "suspended":
      return "suspended";
    case "completed":
      return "completed";
    case "failed":
      return "failed";
    default:
      if (status === "running") return "running";
      if (status === "failed") return "failed";
      if (status === "completed") return "completed";
      return "queued";
  }
}

const AGENT_PHASE_LABELS: Record<AgentPhaseState, string> = {
  running: "",
  queued: "Queued",
  suspended: "Suspended",
  completed: "Completed",
  failed: "Failed",
};

const AGENT_PHASE_TONES: Record<
  AgentPhaseState,
  "running" | "ok" | "error" | "neutral"
> = {
  running: "running",
  queued: "running",
  suspended: "neutral",
  completed: "ok",
  failed: "error",
};

export function resolveAgentPhaseLabel(
  phase: AgentPhase | string | undefined,
  status: string
): string {
  return AGENT_PHASE_LABELS[resolveAgentPhaseState(phase, status)];
}

export function resolveAgentPhaseTone(
  phase: AgentPhase | string | undefined,
  status: string
): "running" | "ok" | "error" | "neutral" {
  return AGENT_PHASE_TONES[resolveAgentPhaseState(phase, status)];
}

export function formatAgentElapsed(display: {
  elapsedMs?: number;
  totalDurationMs?: number;
}): string {
  return (
    formatSubAgentDuration(display.totalDurationMs) ||
    formatSubAgentDuration(display.elapsedMs) ||
    ""
  );
}
