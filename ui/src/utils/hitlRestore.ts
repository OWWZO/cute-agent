import {
  askUserApi,
  dispatchAskUserResume,
} from "@/services/askUser";
import {
  desktopControlApi,
  dispatchDesktopControlResume,
} from "@/services/desktopControl";
import {
  dispatchPlanApprovalResume,
  planApprovalApi,
} from "@/services/planApproval";
import { mergePendingAskUserQuestions } from "@/utils/askUserRestore";
import { mergePendingDesktopControls } from "@/utils/desktopControlRestore";
import { mergePendingPlanApprovals } from "@/utils/planApprovalRestore";
import { claimHitlResumeDispatch } from "@/utils/hitlResumeIntent";

/** bootstrap 一次取回的三类 HITL pending。 */
export interface SessionHitlPending {
  askUser: Record<string, unknown>[];
  desktopControl: Record<string, unknown>[];
  planApproval: Record<string, unknown>[];
}

/**
 * 把三类 HITL pending 合并进会话，并按原语义自动 resume 已决策未续跑项。
 *
 * 与逐个请求版本的唯一区别是数据来源：切换会话走 bootstrap 一次取回，
 * 首屏仍走各自的 pending 请求。合并顺序（ask → desktop → plan）保持不变。
 */
export function applySessionHitlPending(
  conversation: CHAT.ConversationHistory,
  pending: SessionHitlPending
): CHAT.ConversationHistory {
  const withAsk = mergePendingAskUserQuestions(conversation, pending.askUser);
  // 下一帧再派发，确保 ChatView 已挂上 resume 事件监听
  if (withAsk.autoResumes.length) {
    window.setTimeout(() => {
      for (const item of withAsk.autoResumes) {
        // 快速切会话 / StrictMode 双调用可能重复派发同一个 resumeRequestId
        if (!claimHitlResumeDispatch(`ask-user::${item.resumeRequestId}`)) {
          continue;
        }
        dispatchAskUserResume(item);
      }
    }, 0);
  }

  const withDesktop = mergePendingDesktopControls(withAsk.conversation, pending.desktopControl);
  for (const item of withDesktop.autoResumes) {
    dispatchDesktopControlResume(item);
  }

  const withPlan = mergePendingPlanApprovals(withDesktop.conversation, pending.planApproval);
  if (withPlan.autoResumes.length) {
    window.setTimeout(() => {
      for (const item of withPlan.autoResumes) {
        // 快速切会话 / StrictMode 双调用可能重复派发同一个 resumeRequestId
        if (!claimHitlResumeDispatch(`plan-approval::${item.resumeRequestId}`)) {
          continue;
        }
        dispatchPlanApprovalResume(item);
      }
    }, 0);
  }

  return withPlan.conversation;
}

/** 刷新后恢复 AskUser + DesktopControl + PlanApproval pending，并自动 resume 已决策未续跑项 */
export async function restoreHitlForSession(
  conversation: CHAT.ConversationHistory
): Promise<CHAT.ConversationHistory> {
  const sessionId = conversation?.sessionId;
  if (!sessionId) {
    return conversation;
  }

  const [askUser, desktopControl, planApproval] = await Promise.all([
    askUserApi.pending(sessionId).catch((error) => {
      console.warn("恢复 AskUserQuestion pending 失败", error);
      return [];
    }),
    desktopControlApi.pending(sessionId).catch((error) => {
      console.warn("恢复 DesktopControl pending 失败", error);
      return [];
    }),
    planApprovalApi.pending(sessionId).catch((error) => {
      console.warn("恢复 PlanApproval pending 失败", error);
      return [];
    }),
  ]);

  return applySessionHitlPending(conversation, {
    askUser: Array.isArray(askUser) ? askUser : [],
    desktopControl: Array.isArray(desktopControl) ? desktopControl : [],
    planApproval: Array.isArray(planApproval) ? planApproval : [],
  });
}
