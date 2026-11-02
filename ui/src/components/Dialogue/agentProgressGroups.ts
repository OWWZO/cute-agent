import { formatSubAgentDuration } from "@/utils/chat/subagent";
import type { AgentMember, AgentPhase } from "@/types/agentRuntime";

export function resolveAgentPhaseLabel(
  phase: AgentPhase | undefined,
  /** 子 Agent 投影可能还没有确定状态（unknown），其余直接复用运行时联合类型。 */
  status: AgentMember["status"] | "unknown"
): string {
  const normalized = (phase || "").toLowerCase();
  switch (normalized) {
    case "queued":
      return "Queued";
    case "working":
      return "";
    case "suspended":
      return "Suspended";
    case "completed":
      return "Completed";
    case "failed":
      return "Failed";
    default:
      if (status === "running") return "";
      if (status === "failed") return "Failed";
      if (status === "completed") return "Completed";
      return "Queued";
  }
}

export function resolveAgentPhaseTone(
  phaseLabel: string
): "running" | "ok" | "error" | "neutral" {
  if (phaseLabel === "" || phaseLabel === "Queued") return "running";
  if (phaseLabel === "Failed") return "error";
  if (phaseLabel === "Completed") return "ok";
  return "neutral";
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
