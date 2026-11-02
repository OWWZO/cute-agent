import { clonePlanForRender } from "@/utils/chat/renderTasks";

/** 旧协议没有 rounds 时构造的回退轮次 ID；真实 round 不会使用这个值。 */
const LEGACY_LATEST_ROUND_ID = "latest";

export function buildPlannerRoundsForDisplay(
  chat: CHAT.ChatItem,
  streamingThought?: string
): CHAT.PlannerRound[] {
  // SSE 只可能继续原地更新最后一轮；历史轮次保持原引用，
  // 避免每个 token 都深拷贝全部 planner rounds。
  const sourceRounds = Array.isArray(chat.multiAgent?.plannerRounds)
    ? chat.multiAgent.plannerRounds
    : [];
  const rounds = sourceRounds.map((item, index) => {
    if (index !== sourceRounds.length - 1) {
      return item;
    }
    return {
      ...item,
      plan: clonePlanForRender(item.plan),
    };
  });

  if (!rounds.length) {
    // 兼容旧协议：没有 rounds 时，从当前 chat 的单轮 plan/thought 构造展示回退项。
    const fallbackThought = streamingThought ?? chat.thought ?? chat.multiAgent?.plan_thought;
    const fallbackPlan = chat.plan || chat.multiAgent?.plan;
    if (!fallbackThought && !fallbackPlan) {
      return [];
    }
    return [{
      plannerRoundId: LEGACY_LATEST_ROUND_ID,
      planThought: fallbackThought,
      plan: fallbackPlan,
    }];
  }

  if (streamingThought) {
    // 流式 thought 只覆盖最后一轮，历史轮次仍保持已完成的内容。
    const latestIndex = rounds.length - 1;
    rounds[latestIndex] = {
      ...rounds[latestIndex],
      planThought: streamingThought,
    };
  }

  return rounds;
}

export function syncPlannerVersionCursor(
  currentCursor: number | undefined,
  previousRoundCount: number,
  nextRoundCount: number
) {
  // 轮次新增时跟随最新轮次；用户正在查看旧轮次时，只修正越界值而不强行跳转。
  const nextLatestIndex = Math.max(nextRoundCount - 1, 0);
  const previousLatestIndex = Math.max(previousRoundCount - 1, 0);

  if (currentCursor === undefined) {
    return nextLatestIndex;
  }

  if (currentCursor >= previousLatestIndex) {
    return nextLatestIndex;
  }

  return Math.min(Math.max(currentCursor, 0), nextLatestIndex);
}
