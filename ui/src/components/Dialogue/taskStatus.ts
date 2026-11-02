/**
 * HITL 任务状态词表（后端协议字面量，ask_user_question / plan_approval 共用）。
 *
 * `resuming` / `resume_pending` 也算「已回答」：答案已经提交，只是后续续跑
 * 还没结束，时间线不应再把提问卡降级成「待回答」。
 */
export const TASK_STATUS = {
  pending: "pending",
  answered: "answered",
  resuming: "resuming",
  resumePending: "resume_pending",
  success: "success",
  completed: "completed",
} as const;

const ANSWERED_TASK_STATUSES: ReadonlySet<string> = new Set([
  TASK_STATUS.answered,
  TASK_STATUS.resuming,
  TASK_STATUS.resumePending,
  TASK_STATUS.success,
  TASK_STATUS.completed,
]);

export function isAnsweredTaskStatus(status: string): boolean {
  return ANSWERED_TASK_STATUSES.has(status);
}
