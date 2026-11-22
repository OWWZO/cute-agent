import {
  dispatchAskUserResume,
} from "@/services/askUser";
import {
  dispatchDesktopControlResume,
} from "@/services/desktopControl";
import {
  dispatchPlanApprovalResume,
} from "@/services/planApproval";
import { mergePendingAskUserQuestions } from "@/utils/askUserRestore";
import { mergePendingDesktopControls } from "@/utils/desktopControlRestore";
import { mergePendingPlanApprovals } from "@/utils/planApprovalRestore";
import { scheduleHitlResumeDispatch } from "@/utils/hitlResumeIntent";

/** bootstrap 一次取回的三类 HITL pending。 */
export interface SessionHitlPending {
  askUser: Record<string, unknown>[];
  desktopControl: Record<string, unknown>[];
  planApproval: Record<string, unknown>[];
}

/**
 * 把三类 HITL pending 合并进会话，并按原语义自动 resume 已决策未续跑项。
 * 数据统一由会话 bootstrap 一次取回，合并顺序保持 ask → desktop → plan。
 */
export function applySessionHitlPending(
  conversation: CHAT.ConversationHistory,
  pending: SessionHitlPending
): CHAT.ConversationHistory {
  const withAsk = mergePendingAskUserQuestions(conversation, pending.askUser);
  scheduleHitlResumeDispatch(
    "ask-user",
    withAsk.autoResumes,
    dispatchAskUserResume
  );

  const withDesktop = mergePendingDesktopControls(withAsk.conversation, pending.desktopControl);
  for (const item of withDesktop.autoResumes) {
    dispatchDesktopControlResume(item);
  }

  const withPlan = mergePendingPlanApprovals(withDesktop.conversation, pending.planApproval);
  scheduleHitlResumeDispatch(
    "plan-approval",
    withPlan.autoResumes,
    dispatchPlanApprovalResume
  );

  return withPlan.conversation;
}
