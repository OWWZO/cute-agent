import { pickFirstText, resolveTaskResultMap } from "@/utils/chat/toolCalls";

/**
 * 从当前会话 taskList / chat 中推导「输入框上方」要展示的计划。
 * 优先 ExitPlanMode 的 planContent，其次 PlanSolve 结构化 stages。
 */

export type ComposerPlanSource = "plan_approval" | "structured_plan";

export type ComposerPlanModel = {
  source: ComposerPlanSource;
  title: string;
  planContent: string;
  planFilePath?: string;
  approvalId?: string;
  status?: string;
  approved?: boolean;
  feedback?: string;
  /** 结构化步骤（可选） */
  steps?: string[];
  stepStatus?: string[];
};

export function isPlanApprovalApproved(
  status?: string,
  approved?: boolean
): boolean {
  const normalized = String(status || "").trim().toLowerCase();
  return normalized === "approved" || (normalized === "decided" && approved !== false);
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : {};
}

/** 按 `[merged, nested, resultMap, toolAny]` 的顺序取第一个真正的 boolean。 */
function pickBoolean(...values: unknown[]): boolean | undefined {
  for (const value of values) {
    if (typeof value === "boolean") {
      return value;
    }
  }
  return undefined;
}

/** 缺 status 时：已 finish/isFinal 视为 decided，避免续跑后回退审批 UI */
function resolvePlanApprovalStatus(
  tool: CHAT.Task,
  merged: Record<string, unknown>,
  nested: Record<string, unknown>,
  resultMap: Record<string, unknown>,
  toolAny: Record<string, unknown>
): string {
  const raw = String(
    merged.status || nested.status || resultMap.status || toolAny.status || ""
  )
    .trim()
    .toLowerCase();
  if (raw) {
    return raw;
  }
  if (
    tool.finish ||
    tool.isFinal ||
    merged.isFinal === true ||
    resultMap.isFinal === true ||
    nested.isFinal === true
  ) {
    return "decided";
  }
  return "pending";
}

export function pickPlanApprovalFields(tool: CHAT.Task) {
  const resultMap = asRecord(tool.resultMap);
  const nested = asRecord(resultMap.resultMap);
  const merged = asRecord(resolveTaskResultMap(tool));
  const toolAny = tool as unknown as Record<string, unknown>;
  return {
    approvalId: pickFirstText(
      merged.approvalId,
      nested.approvalId,
      resultMap.approvalId,
      toolAny.approvalId,
      tool.messageId
    ),
    planContent: pickFirstText(
      merged.planContent,
      nested.planContent,
      resultMap.planContent,
      toolAny.planContent
    ),
    planFilePath: pickFirstText(
      merged.planFilePath,
      merged.planPath,
      merged.filePath,
      merged.path,
      nested.planFilePath,
      nested.planPath,
      nested.filePath,
      nested.path,
      resultMap.planFilePath,
      resultMap.planPath,
      resultMap.filePath,
      resultMap.path,
      toolAny.planFilePath,
      toolAny.planPath,
      toolAny.filePath
    ),
    status: resolvePlanApprovalStatus(tool, merged, nested, resultMap, toolAny),
    approved: pickBoolean(
      merged.approved,
      nested.approved,
      resultMap.approved,
      toolAny.approved
    ),
    feedback: pickFirstText(
      merged.feedback,
      nested.feedback,
      resultMap.feedback,
      toolAny.feedback
    ),
  };
}

function isPendingPlanApproval(task?: CHAT.Task): boolean {
  if (!task || task.messageType !== "plan_approval") {
    return false;
  }
  return pickPlanApprovalFields(task).status === "pending";
}

function findLatestPendingPlanApproval(
  chat?: CHAT.ChatItem,
  taskList?: CHAT.Task[],
  tasks: CHAT.Task[] = flattenTasks(chat, taskList)
): CHAT.Task | undefined {
  let fallback: CHAT.Task | undefined;
  for (let i = tasks.length - 1; i >= 0; i -= 1) {
    const task = tasks[i];
    if (!isPendingPlanApproval(task)) {
      continue;
    }
    if (pickPlanApprovalFields(task).planContent.trim()) {
      return task;
    }
    fallback ||= task;
  }
  return fallback;
}

function flattenTasks(chat?: CHAT.ChatItem, taskList?: CHAT.Task[]): CHAT.Task[] {
  const flat: CHAT.Task[] = [];
  const approvalIndexes = new Map<string, number>();
  const push = (task?: CHAT.Task) => {
    if (!task) return;
    if (task.messageType === "plan_approval") {
      const fields = pickPlanApprovalFields(task);
      if (fields.approvalId) {
        const previousIndex = approvalIndexes.get(fields.approvalId);
        if (previousIndex != null) {
          const previous = flat[previousIndex];
          if (
            previous &&
            pickPlanApprovalFields(previous).status === "pending" &&
            fields.status !== "pending"
          ) {
            flat[previousIndex] = task;
          }
          return;
        }
        approvalIndexes.set(fields.approvalId, flat.length);
      }
    }
    flat.push(task);
  };

  for (const group of chat?.multiAgent?.tasks || []) {
    for (const task of group || []) push(task as CHAT.Task);
  }
  for (const group of chat?.tasks || []) {
    for (const container of group || []) {
      const children = (container as CHAT.Task).children;
      if (Array.isArray(children) && children.length) {
        for (const child of children) push(child);
      } else {
        push(container as CHAT.Task);
      }
    }
  }
  for (const task of taskList || []) push(task);
  return flat;
}

/**
 * 从后往前找最新 plan_approval（有正文的优先）。
 *
 * `tasks` 供同一次推导里已经展开过任务树的调用方复用，避免重复遍历。
 */
export function findLatestPlanApproval(
  chat?: CHAT.ChatItem,
  taskList?: CHAT.Task[],
  tasks: CHAT.Task[] = flattenTasks(chat, taskList)
): CHAT.Task | undefined {
  // 两件事分开算：先找「最后一个有正文的」，找不到再退回「任意最后一个」。
  // 反向遍历里最先遇到的就是数组末尾那个，所以 fallback 只能在第一次命中时记，
  // 之后不能再覆盖 —— 否则先遇到的空正文卡会被更早的元素顶掉。
  let fallback: CHAT.Task | undefined;
  for (let index = tasks.length - 1; index >= 0; index -= 1) {
    const task = tasks[index];
    if (task?.messageType !== "plan_approval") {
      continue;
    }
    if (pickPlanApprovalFields(task).planContent.trim()) {
      return task;
    }
    fallback ??= task;
  }
  return fallback;
}

export function buildComposerPlanModel(params: {
  chat?: CHAT.ChatItem;
  taskList?: CHAT.Task[];
  structuredPlan?: CHAT.Plan;
}): ComposerPlanModel | null {
  const { chat, taskList, structuredPlan } = params;

  // 同一棵任务树只展开一次，两个 finder 共用；pickPlanApprovalFields 也尽量只算一次。
  const tasks = flattenTasks(chat, taskList);
  const latestApprovalTask = findLatestPlanApproval(chat, taskList, tasks);
  const approvalTask = findLatestPendingPlanApproval(chat, taskList, tasks);
  const approvalFields = approvalTask
    ? pickPlanApprovalFields(approvalTask)
    : undefined;
  if (approvalTask && approvalFields) {
    const fields = approvalFields;
    if (fields.planContent.trim() || fields.approvalId) {
      return {
        source: "plan_approval",
        // approvalTask 来自 findLatestPendingPlanApproval，status 恒为 pending；
        // plan_approval 的实际标题由 PlanComposerBar 按本地状态覆盖，这里只给兜底。
        title: "实现计划",
        planContent: fields.planContent,
        planFilePath: fields.planFilePath || undefined,
        approvalId: fields.approvalId || undefined,
        status: fields.status,
        approved: fields.approved,
        feedback: fields.feedback || undefined,
      };
    }
  }

  // 历史中的已决策 approval 只在时间线保留只读记录，不能回到底部再次伪装成审批。
  // 已批准计划没有结构化进度时隐藏旧条，避免历史记录再次显示过期计划。
  if (latestApprovalTask) {
    const latestFields =
      latestApprovalTask === approvalTask && approvalFields
        ? approvalFields
        : pickPlanApprovalFields(latestApprovalTask);
    const approvedAndSettled =
      latestFields.status !== "pending" && latestFields.approved !== false;
    if (
      approvedAndSettled &&
      !structuredPlan?.stages?.length &&
      !structuredPlan?.steps?.length
    ) {
      return null;
    }
  }

  if (structuredPlan?.stages?.length || structuredPlan?.steps?.length) {
    const steps = structuredPlan.steps?.length
      ? structuredPlan.steps
      : structuredPlan.stages || [];
    const lines = steps.map((step, index) => {
      const status = structuredPlan.stepStatus?.[index] || "not_started";
      const mark =
        status === "completed" ? "[x]" : status === "in_progress" ? "[~]" : "[ ]";
      return `${mark} ${step}`;
    });
    return {
      source: "structured_plan",
      title: structuredPlan.title || "研究路线",
      planContent: lines.join("\n"),
      steps: structuredPlan.steps,
      stepStatus: structuredPlan.stepStatus as string[] | undefined,
    };
  }

  return null;
}
