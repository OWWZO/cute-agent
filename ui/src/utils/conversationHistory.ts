import type {
  ConversationHistoryDetail,
  ConversationHistoryPage,
  ConversationRunReplay,
  ConversationRunSummary,
} from "@/services/agentConversation";
import { GENERIC_TASK_PRODUCT } from "@/utils/constants";
import {
  AGENT_TYPE_DEEP_THINK,
  AGENT_TYPE_PLAN_SOLVE,
} from "@/utils/agentMode";
import {
  hasRenderableTimelineItems,
  isRunReplay,
  resolveRunReplayFrames,
} from "@/utils/timelineItems";
import { buildSessionTimelineChat } from "./chat/sessionTimelineChat";

type ConversationHistoryTitleDetail = Pick<
  ConversationHistoryPage,
  "title" | "sessionId"
> & {
  runs?: Array<ConversationRunSummary | ConversationRunReplay>;
};

/**
 * 会话详情为空时，首页应保持当前空白/初始态，不自动切到其他会话。
 */
export function isHistoryDetailEmpty(
  detail?: ConversationHistoryPage | ConversationHistoryDetail | null
) {
  return !detail || !Array.isArray(detail.runs) || detail.runs.length === 0;
}

export function toConversationHistoryTitle(detail?: ConversationHistoryTitleDetail | null) {
  if (!detail) {
    return "新对话";
  }
  const normalizedTitle = String(detail.title || "").trim();
  if (normalizedTitle && normalizedTitle !== "新对话") {
    return normalizedTitle;
  }

  // 兼容旧的 Plan continuation：session head 可能被空 query 覆盖，但首个 run 仍保留原问题。
  const firstQuery = detail.runs?.find(
    (run) => getRunQuery(run).trim()
  );
  const firstQueryText = firstQuery ? getRunQuery(firstQuery) : "";
  const normalizedQuery = firstQueryText.trim();
  if (normalizedQuery) {
    return normalizedQuery.length <= 30
      ? normalizedQuery
      : normalizedQuery.slice(0, 30);
  }

  return normalizedTitle || (detail.sessionId ? `会话 ${detail.sessionId}` : "新对话");
}

/**
 * 首次 session 请求只还原 run 的轻量摘要壳，不读取响应中的 replayFrames。
 */
export function hydrateConversationFromSummaryPage(
  page: ConversationHistoryPage
): CHAT.ConversationHistory {
  const chatList = (page.runs || []).map((run) => hydrateRunSummary(page, run));
  return buildConversationHistory(page, chatList);
}

/**
 * 兼容已有 rich detail 调用方：有 replayFrames 的 run 走完整回放，没有的 run
 * 仍保持为 summary shell。
 */
export function hydrateConversationFromReplayFrames(
  detail: ConversationHistoryPage | ConversationHistoryDetail
): CHAT.ConversationHistory {
  const chatList = (detail.runs || []).map((run) => {
    if (hasReplayFrames(run)) {
      return hydrateRunReplay(detail, run as ConversationRunReplay);
    }
    return hydrateRunSummary(detail, run);
  });
  return buildConversationHistory(detail, chatList);
}

export function hydrateRunSummary(
  detail: ConversationHistoryPage | ConversationHistoryDetail,
  run: ConversationRunSummary | ConversationRunReplay
): CHAT.ChatItem {
  return buildSessionTimelineChat({
    sessionId: detail.sessionId,
    run: toRunSummary(run),
    fallbackQuery: getRunQuery(run),
    agentType: resolveConversationAgentType(detail.deepThink),
  });
}

export function hydrateRunReplay(
  detail: ConversationHistoryPage | ConversationHistoryDetail,
  run: ConversationRunReplay
): CHAT.ChatItem {
  return buildSessionTimelineChat({
    sessionId: detail.sessionId,
    run: toRunSummary(run),
    source: {
      items: run.timelineItems || [],
      replayFrames: resolveRunReplayFrames(run),
      status: run.status,
      loaded: true,
    },
    fallbackQuery: getRunQuery(run),
    agentType: resolveConversationAgentType(detail.deepThink),
    settleResultMetadata: true,
    isolatedSession: false,
  });
}

/**
 * 把下一页 summary 合并到现有会话。已有 requestId 的 chat 保持原对象，避免
 * 分页响应把已经 replay 或仍在流式更新的 run 覆盖掉。
 */
export function mergeConversationHistoryPage(
  conversation: CHAT.ConversationHistory,
  page: ConversationHistoryPage
): CHAT.ConversationHistory {
  const incoming = (page.runs || []).map((run) => hydrateRunSummary(page, run));
  const chatList = mergeSummaryChatsByRequestId(conversation.chatList, incoming);
  const createdAt = toTimestamp(page.startedAt, conversation.createdAt);
  const updatedAt = toTimestamp(page.lastActiveAt, conversation.updatedAt);
  const title =
    conversation.title && conversation.title !== "新对话"
      ? conversation.title
      : conversation.chatTitle || toConversationHistoryTitle(page);

  return {
    ...conversation,
    sessionId: page.sessionId || conversation.sessionId,
    title,
    deepThink: Boolean(page.deepThink),
    createdAt,
    updatedAt,
    chatTitle: title,
    chatList,
    historyNextCursor: page.nextCursor ?? null,
    historyHasMore: page.hasMore ?? Boolean(page.nextCursor),
  };
}

/**
 * 用单 run rich replay 替换目标 requestId；其它 run、已加载状态和顺序全部保留。
 * 若目标不在当前 summary 页，直接追加，供 follow_idle 恢复漏掉的 run。
 */
export function mergeRunReplayIntoConversation(
  conversation: CHAT.ConversationHistory,
  replay: ConversationRunReplay
): CHAT.ConversationHistory {
  const detail: ConversationHistoryPage = {
    sessionId: conversation.sessionId,
    title: conversation.title,
    status: "",
    deepThink: conversation.deepThink,
    runCount: conversation.chatList.length,
    finishedRunCount: 0,
    failedRunCount: 0,
    runs: [],
  };
  const replayChat = hydrateRunReplay(detail, replay);
  const nextChatList: CHAT.ChatItem[] = [];
  const seenRequestIds = new Set<string>();
  let replaced = false;

  for (const chat of conversation.chatList || []) {
    const requestId = chat.requestId;
    if (requestId && seenRequestIds.has(requestId)) {
      continue;
    }
    if (requestId) {
      seenRequestIds.add(requestId);
    }
    if (requestId === replay.requestId) {
      if (!replaced) {
        nextChatList.push(replayChat);
        replaced = true;
      }
      continue;
    }
    nextChatList.push(chat);
  }

  if (!replaced) {
    nextChatList.push(replayChat);
  }

  return {
    ...conversation,
    chatList: nextChatList,
  };
}

function buildConversationHistory(
  detail: ConversationHistoryPage | ConversationHistoryDetail,
  chatList: CHAT.ChatItem[]
): CHAT.ConversationHistory {
  const createdAt = toTimestamp(detail.startedAt);
  const updatedAt = toTimestamp(detail.lastActiveAt, createdAt);
  const title = toConversationHistoryTitle(detail);

  return {
    id: `conversation-${detail.sessionId}`,
    sessionId: detail.sessionId,
    title,
    productType: GENERIC_TASK_PRODUCT.type,
    deepThink: Boolean(detail.deepThink),
    sessionModel: detail.model ?? null,
    sessionThinking: detail.thinking ?? null,
    sessionThinkingEffort: detail.thinkingEffort ?? null,
    createdAt,
    updatedAt,
    chatTitle: title,
    chatList,
    dataChatList: [],
    historyNextCursor: detail.nextCursor ?? null,
    historyHasMore: detail.hasMore ?? Boolean(detail.nextCursor),
  };
}

function mergeSummaryChatsByRequestId(
  existing: CHAT.ChatItem[],
  incoming: CHAT.ChatItem[]
) {
  const result: CHAT.ChatItem[] = [];
  const seenRequestIds = new Set<string>();

  for (const chat of existing || []) {
    const requestId = chat.requestId;
    if (requestId && seenRequestIds.has(requestId)) {
      continue;
    }
    if (requestId) {
      seenRequestIds.add(requestId);
    }
    result.push(chat);
  }

  for (const chat of incoming) {
    const requestId = chat.requestId;
    if (!requestId || seenRequestIds.has(requestId)) {
      continue;
    }
    seenRequestIds.add(requestId);
    result.push(chat);
  }

  return result;
}

function hasReplayFrames(run: ConversationRunSummary | ConversationRunReplay) {
  return isRunReplay(run) && (
    (run.replayFrames?.length ?? 0) > 0
    || hasRenderableTimelineItems(run.timelineItems)
  );
}

function getRunQuery(run: ConversationRunSummary | ConversationRunReplay) {
  return isRunReplay(run)
    ? String(run.queryText || "")
    : String(run.queryPreview || "");
}

function toRunSummary(
  run: ConversationRunSummary | ConversationRunReplay
): ConversationRunSummary {
  if (!isRunReplay(run)) {
    return run;
  }
  return {
    requestId: run.requestId,
    status: run.status,
    queryPreview: run.queryText,
    finalSummaryPreview: run.finalSummaryText,
    finalSummaryText: run.finalSummaryText,
    entryAgent: run.entryAgent,
    contextUsage: run.contextUsage,
    llmCallCount: run.llmCallCount,
    toolCallCount: run.toolCallCount,
    artifactCount: run.artifactCount,
    startedAt: run.startedAt,
    finishedAt: run.finishedAt,
    durationMs: run.durationMs,
    hasReplay: true,
  };
}

function toTimestamp(value?: string | null, fallback = Date.now()) {
  if (!value) {
    return fallback;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function resolveConversationAgentType(deepThink?: boolean) {
  return deepThink ? AGENT_TYPE_DEEP_THINK : AGENT_TYPE_PLAN_SOLVE;
}
