export type CollaborationMode = "normal" | "cluster";

export const AGENT_TYPE_PLAN_SOLVE = 3;
export const AGENT_TYPE_REACT = 5;

export function normalizeCollaborationMode(value?: string | null): CollaborationMode {
  return value === "cluster" ? "cluster" : "normal";
}

export function isPlanSolveConversation(
  agentType?: number,
  collaborationMode?: CollaborationMode
) {
  return collaborationMode === "cluster" || agentType === AGENT_TYPE_PLAN_SOLVE;
}
