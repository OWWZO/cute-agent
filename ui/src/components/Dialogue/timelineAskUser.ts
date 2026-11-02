import { TASK_STATUS, isAnsweredTaskStatus } from "./taskStatus";

export function isAnsweredAskUserTask(tool: CHAT.Task): boolean {
  const resultMap = (tool.resultMap || {}) as Record<string, unknown>;
  const nested = (resultMap.resultMap || resultMap) as Record<string, unknown>;
  const toolAny = tool as unknown as Record<string, unknown>;
  const askStatus = String(
    nested.status || resultMap.status || toolAny.status || TASK_STATUS.pending
  ).trim().toLowerCase();
  return (
    isAnsweredTaskStatus(askStatus) ||
    Boolean(resultMap.isFinal && askStatus !== TASK_STATUS.pending) ||
    Boolean(nested.answers || resultMap.answers || toolAny.answers)
  );
}
