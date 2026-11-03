export const AGENT_TYPE_DEEP_THINK = 3;
export const AGENT_TYPE_PLAN_SOLVE = 5;

export function isPlanSolveConversation(
  agentType?: number,
  deepThink?: boolean
) {
  return Boolean(deepThink) || agentType === AGENT_TYPE_DEEP_THINK;
}

export function isStructuredConversation(
  agentType?: number,
  deepThink?: boolean
) {
  return isPlanSolveConversation(agentType, deepThink);
}
