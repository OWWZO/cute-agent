import type {
  ConversationHistoryPage,
  ConversationRunSummary,
} from "@/services/agentConversation";
import type { SessionRunTimelineSource } from "@/hooks/useSessionTimeline";
import {
  buildConversationTaskData,
  combineData,
} from "@/utils/chat";
import { extractArtifactSection } from "@/utils/artifactContent";
import { normalizeRunStatus } from "@/utils/runStatus";
import {
  isRunFinalReplayFrame,
  isRunFinalStatus,
  isRunFinalTimelineItem,
} from "@/utils/timeline/timelineStore";
import { applyTimelineSourcesToChat } from "./timelineProjection";

export type BuildSessionTimelineRoundsInput = {
  sessionId: string;
  history?: ConversationHistoryPage | null;
  sources?: Record<string, SessionRunTimelineSource>;
  pendingRun?: { requestId: string; query: string } | null;
  fallbackQuery?: string;
  includeFallbackRun?: boolean;
};

function asText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function createSessionRunShell(
  sessionId: string,
  run: ConversationRunSummary,
  fallbackQuery: string | undefined,
  loaded: boolean,
  agentType?: CHAT.ChatItem["agentType"]
): CHAT.ChatItem {
  const status = normalizeRunStatus(run.status);
  return {
    sessionId,
    requestId: run.requestId,
    query: asText(run.queryPreview) || asText(fallbackQuery),
    files: [],
    responseType: "txt",
    agentType,
    loading: status === "RUNNING",
    forceStop: status === "STOPPED",
    tasks: [],
    thought: "",
    response: "",
    taskStatus: 0,
    tip: status === "WAITING_INPUT" ? "需要你的帮助" : "",
    multiAgent: { tasks: [] },
    timeline: [],
    startedAt: run.startedAt,
    finishedAt: run.finishedAt,
    replayLoaded: loaded,
    replayAvailable: run.hasReplay,
    metrics: { status },
  } as CHAT.ChatItem;
}

function applyRunMetadata(chat: CHAT.ChatItem, statusValue: string, run: ConversationRunSummary) {
  const status = normalizeRunStatus(statusValue || run.status);
  chat.loading = status === "RUNNING";
  chat.forceStop = status === "STOPPED";
  chat.metrics = {
    ...(chat.metrics || {}),
    status,
  };
  if (status === "WAITING_INPUT") {
    chat.loading = false;
    chat.tip = "需要你的帮助";
  }
  if (typeof run.durationMs === "number" && Number.isFinite(run.durationMs)) {
    chat.runDurationMs = run.durationMs;
    chat.runTimingSource = "ledger";
  }
  if (run.contextUsage) {
    chat.contextUsage = {
      max: run.contextUsage.max,
      promptTokens: run.contextUsage.promptTokens,
    };
  }
}

function applyLegacySummaryConclusion(
  chat: CHAT.ChatItem,
  run: ConversationRunSummary
): CHAT.Task | undefined {
  if (chat.conclusion || !isRunFinalStatus(normalizeRunStatus(run.status))) {
    return chat.conclusion;
  }
  const summary = asText(run.finalSummaryText) || asText(run.finalSummaryPreview);
  if (!summary) {
    return undefined;
  }
  const artifactKeys = extractArtifactSection(summary)?.artifactKeys || [];
  const messageId = `${run.requestId}-summary`;
  chat.conclusion = {
    id: messageId,
    messageId,
    messageType: "result",
    requestId: run.requestId,
    result: summary,
    taskSummary: summary,
    finish: true,
    isFinal: true,
    ...(artifactKeys.length ? { artifactKeys } : {}),
    resultMap: {
      result: summary,
      taskSummary: summary,
      isFinal: true,
      finish: true,
      fileList: [],
      ...(artifactKeys.length ? { artifactKeys } : {}),
    } as unknown as MESSAGE.ResultMap,
  } as unknown as CHAT.Task;
  return chat.conclusion;
}

function applySourceFallbackConclusion(
  chat: CHAT.ChatItem,
  run: ConversationRunSummary,
  statusValue?: string
) {
  if (chat.conclusion || !isRunFinalStatus(normalizeRunStatus(statusValue || run.status))) {
    return;
  }
  const summary = asText(run.finalSummaryText) || asText(run.finalSummaryPreview);
  if (!summary) {
    return;
  }
  const artifactKeys = extractArtifactSection(summary)?.artifactKeys || [];
  const messageId = `${run.requestId}-summary`;
  combineData({
    taskId: run.requestId || `${Date.now()}`,
    taskOrder: 1,
    messageType: "task",
    messageOrder: 1,
    messageId,
    resultMap: {
      requestId: run.requestId,
      messageId,
      messageType: "result",
      messageTime: String(run.finishedAt || run.startedAt || Date.now()),
      result: summary,
      taskSummary: summary,
      fileList: [],
      isFinal: true,
      finish: true,
      ...(artifactKeys.length ? { artifactKeys } : {}),
    },
  } as unknown as MESSAGE.EventData, chat);
}

/**
 * 一个 run 一个 ChatItem。canonical source 优先，旧数据才读取 replayFrames。
 */
export function buildSessionTimelineChat(input: {
  sessionId: string;
  run: ConversationRunSummary;
  source?: SessionRunTimelineSource;
  fallbackQuery?: string;
  agentType?: CHAT.ChatItem["agentType"];
  settleResultMetadata?: boolean;
  isolatedSession?: boolean;
}): CHAT.ChatItem {
  const {
    sessionId,
    run,
    source,
    fallbackQuery,
    agentType,
    settleResultMetadata = false,
    isolatedSession = true,
  } = input;
  const chat = createSessionRunShell(
    sessionId,
    run,
    fallbackQuery,
    Boolean(source?.loaded),
    agentType
  );
  let explicitConclusion: CHAT.Task | undefined;
  let legacyConclusion: CHAT.Task | undefined;
  if (source) {
    const sourceStatus = source.status || run.status;
    explicitConclusion = applyTimelineSourcesToChat(chat, {
      items: source.items,
      replayFrames: source.replayFrames,
      allowLegacyResult: isRunFinalStatus(normalizeRunStatus(sourceStatus)),
      settleResultMetadata,
      isolatedSession,
    });
    applySourceFallbackConclusion(chat, run, sourceStatus);
  } else {
    legacyConclusion = applyLegacySummaryConclusion(chat, run);
  }
  applyRunMetadata(chat, source?.status || run.status, run);
  const derived = buildConversationTaskData(chat, false).currentChat;
  if (source) {
    const derivedConclusion = derived.conclusion;
    derived.conclusion = explicitConclusion
      || (derivedConclusion?.messageType === "task_summary"
        ? undefined
        : derivedConclusion);
  } else {
    derived.conclusion = legacyConclusion;
  }
  return derived;
}

/**
 * 用 canonical run source 重投影一条已在控制流中的 ChatItem。
 *
 * 主 Agent 仍通过 useConversationStream 处理发送、停止、HITL 和后台唤醒；
 * 可见的 process/conclusion/File 卡只从 canonical source 生成，避免两套规则。
 */
export function projectChatWithTimelineSource(
  chat: CHAT.ChatItem,
  source: SessionRunTimelineSource
): CHAT.ChatItem {
  const base: CHAT.ChatItem = {
    ...chat,
    tasks: [],
    thought: "",
    response: "",
    conclusion: undefined,
    timeline: [],
    multiAgent: {
      ...(chat.multiAgent || {}),
      tasks: [],
      plan: undefined,
      plan_thought: undefined,
    },
  };
  const explicitConclusion = applyTimelineSourcesToChat(base, {
    items: source.items,
    replayFrames: source.replayFrames,
    allowLegacyResult: isRunFinalStatus(
      normalizeRunStatus(source.status || chat.metrics?.status)
    ),
    settleResultMetadata: false,
  });
  const hasCanonicalFinal =
    (source.items || []).some(isRunFinalTimelineItem)
    || (source.replayFrames || []).some(isRunFinalReplayFrame);
  const derived = buildConversationTaskData(base, false).currentChat;
  return {
    ...chat,
    tasks: derived.tasks,
    thought: derived.thought,
    response: derived.response,
    // 终答是 run 的最后一个 committed item，可能晚于工具卡和聊天终态。
    // run-final 尚未到达时保留控制流已经拿到的结论，避免用半条时间线覆盖它。
    conclusion: hasCanonicalFinal ? explicitConclusion : chat.conclusion,
    timeline: derived.timeline,
    multiAgent: {
      ...(chat.multiAgent || {}),
      ...(derived.multiAgent || {}),
      plan: derived.multiAgent?.plan,
      plan_thought: derived.multiAgent?.plan_thought,
    },
    metrics: {
      ...(chat.metrics || {}),
      ...(source.status ? { status: normalizeRunStatus(source.status) } : {}),
    },
    loading: source.status
      ? normalizeRunStatus(source.status) === "RUNNING"
      : chat.loading,
  };
}

/**
 * 会话历史与 canonical run sources 的唯一 ChatItem 投影入口。
 */
export function buildSessionTimelineRounds(
  input: BuildSessionTimelineRoundsInput
): CHAT.ChatItem[] {
  const {
    sessionId,
    history,
    sources,
    pendingRun,
    fallbackQuery,
    includeFallbackRun = true,
  } = input;
  const runs: ConversationRunSummary[] = Array.isArray(history?.runs)
    ? history.runs.map((run) => ({ ...run }))
    : [];
  const knownRequestIds = new Set(runs.map((run) => run.requestId));
  for (const [requestId, source] of Object.entries(sources || {})) {
    if (!requestId || knownRequestIds.has(requestId)) {
      continue;
    }
    // history 索引可能晚于 live timeline。子会话并发启动时如果只认 history.runs，
    // 已经收到 childRequestId 的实时 run 会被误判为“没有轨迹”。
    runs.push({
      requestId,
      status: source.status || "RUNNING",
      queryPreview:
        pendingRun?.requestId === requestId
          ? pendingRun.query
          : undefined,
      hasReplay: false,
      startedAt: undefined,
      finishedAt: undefined,
    });
    knownRequestIds.add(requestId);
  }
  if (pendingRun && !runs.some((run) => run.requestId === pendingRun.requestId)) {
    runs.push({
      requestId: pendingRun.requestId,
      status: "RUNNING",
      queryPreview: pendingRun.query,
      hasReplay: false,
      startedAt: new Date().toISOString(),
    });
  }
  if (runs.length === 0) {
    if (!includeFallbackRun) {
      return [];
    }
    const fallbackRun: ConversationRunSummary = {
      requestId: "",
      status: "RUNNING",
      queryPreview: fallbackQuery,
      hasReplay: false,
    };
    return [buildSessionTimelineChat({
      sessionId,
      run: fallbackRun,
      fallbackQuery,
    })];
  }
  return runs.map((run, index) => buildSessionTimelineChat({
    sessionId,
    run,
    source: sources?.[run.requestId],
    fallbackQuery: index === 0 ? fallbackQuery : undefined,
  }));
}

export default buildSessionTimelineRounds;
