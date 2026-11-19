import type { AgentTimelineItem } from "@/types/timeline";
import type { ConversationReplayFrame } from "@/services/agentConversation";

/**
 * 主 Agent 与子 Agent 共用的时间线合并规则。
 *
 * committed item 和 live transient item 都以 itemId 为唯一身份；没有 itemId 的旧帧
 * 只在 legacy adapter 中按顺序消费，不能用来伪造新的持久化身份。
 */
export class TimelineStore {
  private readonly items = new Map<string, AgentTimelineItem>();

  constructor(initial: AgentTimelineItem[] = []) {
    this.upsertMany(initial);
  }

  upsertMany(items: AgentTimelineItem[] | undefined): void {
    for (const item of items || []) {
      if (!item) {
        continue;
      }
      const key = timelineItemKey(item);
      if (!key) {
        continue;
      }
      const previous = this.items.get(key);
      const normalized = mergeDefined(previous, item);
      normalized.itemId = key;
      if (previous?.orderKey && previous.orderKey > 0) {
        normalized.orderKey = previous.orderKey;
      }
      normalized.delta = item.eventType === "item.delta" && item.delta
        ? `${previous?.delta || ""}${item.delta}`
        : previous?.delta || item.delta || null;
      normalized.payload = mergePayload(previous?.payload, item.payload);
      normalized.responseAll = item.responseAll || previous?.responseAll;
      this.items.set(key, normalized);
    }
  }

  values(): AgentTimelineItem[] {
    return [...this.items.values()].sort(compareTimelineItems);
  }
}

const DEEP_SEARCH_STAGES = new Set([
  "extend",
  "search",
  "chapter_summary",
  "report",
]);

function mergePayload(
  previous: AgentTimelineItem["payload"],
  next: AgentTimelineItem["payload"],
): AgentTimelineItem["payload"] {
  if (!next) {
    return previous;
  }
  const merged = { ...(previous || {}), ...next };
  const nextEvent = deepSearchEventData(next.eventData);
  if (!nextEvent) {
    return merged;
  }

  const history = Array.isArray(previous?.eventDataHistory)
    ? [...previous.eventDataHistory]
    : [];
  // replay 快照本身可能已经携带完整阶段历史。每次 upsert 都要把这些阶段
  // 合并进来，不能只看 eventData（它通常只是最后一帧 report），否则会把
  // 前面已经完成的查询/章节卡全部丢掉。
  for (const eventData of next.eventDataHistory || []) {
    const stageEvent = deepSearchEventData(eventData);
    if (stageEvent) {
      mergeDeepSearchEvent(history, stageEvent);
    }
  }
  if (!history.length) {
    const previousEvent = deepSearchEventData(previous?.eventData);
    if (previousEvent) {
      history.push(previousEvent);
    }
  }
  mergeDeepSearchEvent(history, nextEvent);
  const latest = history[history.length - 1] || nextEvent;
  return {
    ...merged,
    eventData: latest,
    eventDataHistory: history,
  };
}

function mergeDeepSearchEvent(
  history: MESSAGE.EventData[],
  incoming: MESSAGE.EventData,
): void {
  const incomingStage = deepSearchStage(incoming);
  if (!incomingStage) {
    history.push(incoming);
    return;
  }
  const identity = deepSearchStageIdentity(incomingStage);
  const index = history.findIndex((event) => {
    const stage = deepSearchStage(event);
    return stage ? deepSearchStageIdentity(stage) === identity : false;
  });
  if (index < 0) {
    history.push(incoming);
    return;
  }

  const previous = history[index];
  const previousStage = deepSearchStage(previous);
  if (incomingStage.stage === "report" && previousStage) {
    history[index] = mergeDeepSearchReport(previousStage.payload, incoming, incomingStage.payload);
    return;
  }
  if (incomingStage.stage === "search" && previousStage) {
    history[index] = mergeDeepSearchSearch(previousStage.payload, incoming, incomingStage.payload);
    return;
  }
  if (incomingStage.stage === "chapter_summary" && previousStage) {
    const previousSearch = history.find((event) => {
      const stage = deepSearchStage(event);
      return stage?.stage === "search";
    });
    history[index] = mergeDeepSearchChapter(
      deepSearchStage(previousSearch)?.payload || previousStage.payload,
      incoming,
      incomingStage.payload,
    );
    return;
  }
  history[index] = incoming;
}

function deepSearchStageIdentity(stage: DeepSearchStageSnapshot): string {
  if (stage.stage !== "chapter_summary") {
    return stage.stage;
  }
  return [
    "chapter_summary",
    stage.payload.chapterId || "",
    stage.payload.chapterTitle || "",
    stage.payload.chapterOrder ?? "",
  ].join(":");
}

function deepSearchStage(
  eventData: MESSAGE.EventData | undefined,
): DeepSearchStageSnapshot | undefined {
  if (!eventData || typeof eventData !== "object") {
    return undefined;
  }
  const outer = asRecord(eventData.resultMap);
  const nested = asRecord(outer?.resultMap);
  const payload = nested && DEEP_SEARCH_STAGES.has(String(nested.messageType || ""))
    ? nested
    : outer && DEEP_SEARCH_STAGES.has(String(outer.messageType || ""))
      ? outer
      : undefined;
  if (!payload) {
    return undefined;
  }
  return {
    stage: String(payload.messageType),
    payload,
  };
}

function deepSearchEventData(value: unknown): MESSAGE.EventData | undefined {
  if (!value || typeof value !== "object") {
    return undefined;
  }
  const eventData = value as MESSAGE.EventData;
  return deepSearchStage(eventData) ? eventData : undefined;
}

function mergeDeepSearchSearch(
  previousPayload: Record<string, unknown>,
  incoming: MESSAGE.EventData,
  incomingPayload: Record<string, unknown>,
): MESSAGE.EventData {
  const previousResult = asRecord(previousPayload.searchResult);
  const incomingResult = asRecord(incomingPayload.searchResult);
  if (!previousResult || !incomingResult) {
    return incoming;
  }

  const queries = [...new Set([
    ...stringList(previousResult.query),
    ...stringList(incomingResult.query),
  ])];
  const docsByQuery = new Map<string, unknown[]>();
  const previousQueries = stringList(previousResult.query);
  const previousDocs = Array.isArray(previousResult.docs) ? previousResult.docs : [];
  previousQueries.forEach((query, index) => {
    docsByQuery.set(query, Array.isArray(previousDocs[index]) ? [...previousDocs[index]] : []);
  });
  const incomingQueries = stringList(incomingResult.query);
  const incomingDocs = Array.isArray(incomingResult.docs) ? incomingResult.docs : [];
  incomingQueries.forEach((query, index) => {
    docsByQuery.set(
      query,
      Array.isArray(incomingDocs[index]) ? [...incomingDocs[index]] : [],
    );
  });

  return replaceDeepSearchPayload(incoming, {
    ...incomingPayload,
    searchResult: {
      ...previousResult,
      ...incomingResult,
      query: queries,
      docs: queries.map((query) => docsByQuery.get(query) || []),
    },
  });
}

function mergeDeepSearchReport(
  previousPayload: Record<string, unknown>,
  incoming: MESSAGE.EventData,
  incomingPayload: Record<string, unknown>,
): MESSAGE.EventData {
  if (incomingPayload.isFinal) {
    return incoming;
  }
  const previousAnswer = String(previousPayload.answer || "");
  const incomingAnswer = String(incomingPayload.answer || "");
  const answer = !incomingAnswer || previousAnswer.endsWith(incomingAnswer)
    ? previousAnswer
    : incomingAnswer.startsWith(previousAnswer)
      ? incomingAnswer
      : `${previousAnswer}${incomingAnswer}`;
  return replaceDeepSearchPayload(incoming, {
    ...incomingPayload,
    answer,
  });
}

function mergeDeepSearchChapter(
  previousPayload: Record<string, unknown>,
  incoming: MESSAGE.EventData,
  incomingPayload: Record<string, unknown>,
): MESSAGE.EventData {
  const previousSummary = String(previousPayload.chapterSummary || previousPayload.answer || "");
  const incomingSummary = String(incomingPayload.chapterSummary || incomingPayload.answer || "");
  const summary =
    !incomingSummary || previousSummary.endsWith(incomingSummary)
      ? previousSummary
      : incomingSummary.startsWith(previousSummary)
        ? incomingSummary
        : `${previousSummary}${incomingSummary}`;
  const previousResult = asRecord(previousPayload.searchResult);
  const incomingResult = asRecord(incomingPayload.searchResult);
  const searchResult =
    previousResult || incomingResult
      ? {
        ...(previousResult || {}),
        ...(incomingResult || {}),
        query:
          stringList(incomingResult?.query).length
            ? stringList(incomingResult?.query)
            : stringList(previousResult?.query),
        docs:
          Array.isArray(incomingResult?.docs) && incomingResult.docs.length
            ? incomingResult.docs
            : previousResult?.docs,
      }
      : undefined;
  return replaceDeepSearchPayload(incoming, {
    ...previousPayload,
    ...incomingPayload,
    ...(searchResult ? { searchResult } : {}),
    chapterSummary: summary,
    answer: summary,
  });
}

function replaceDeepSearchPayload(
  eventData: MESSAGE.EventData,
  payload: Record<string, unknown>,
): MESSAGE.EventData {
  const outer = asRecord(eventData.resultMap);
  if (!outer) {
    return eventData;
  }
  if (asRecord(outer.resultMap)) {
    return {
      ...eventData,
      resultMap: {
        ...outer,
        resultMap: payload,
      },
    } as unknown as MESSAGE.EventData;
  }
  return {
    ...eventData,
    resultMap: payload as unknown as MESSAGE.Task,
  };
}

function stringList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.map((item) => String(item || "")).filter(Boolean)
    : [];
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

type DeepSearchStageSnapshot = {
  stage: string;
  payload: Record<string, unknown>;
};

function mergeDefined(
  previous: AgentTimelineItem | undefined,
  next: AgentTimelineItem
): AgentTimelineItem {
  const merged: AgentTimelineItem = { ...(previous || {}) };
  for (const [key, value] of Object.entries(next)) {
    if (value !== undefined) {
      (merged as unknown as Record<string, unknown>)[key] = value;
    }
  }
  return merged;
}

export function timelineItemKey(item: AgentTimelineItem): string {
  return String(item.itemId || "").trim();
}

export function compareTimelineItems(
  left: AgentTimelineItem,
  right: AgentTimelineItem
): number {
  const order = Number(left.orderKey || left.seq || 0) - Number(right.orderKey || right.seq || 0);
  if (order !== 0) {
    return order;
  }
  const subOrder = Number(left.subOrder || 0) - Number(right.subOrder || 0);
  if (subOrder !== 0) {
    return subOrder;
  }
  return String(left.itemId || "").localeCompare(String(right.itemId || ""));
}

/** 只有 run-final 拥有底部终答身份。 */
export function isRunFinalTimelineItem(
  item: Pick<AgentTimelineItem, "itemId" | "kind" | "phase"> | undefined
): boolean {
  return Boolean(
    item
      && item.phase === "FINAL"
      && item.kind === "ASSISTANT_MESSAGE"
      && String(item.itemId || "").startsWith("run:")
      && String(item.itemId || "").endsWith(":final")
  );
}

export function isRunTimelineTerminal(status?: string): boolean {
  return ["SUCCESS", "FAILED", "STOPPED", "TIMEOUT", "WAITING_INPUT"].includes(
    String(status || "").toUpperCase()
  );
}

/** 只有真正交付最终答复的 run 状态才允许产生 conclusion。 */
export function isRunFinalStatus(status?: string): boolean {
  return ["SUCCESS", "FAILED", "STOPPED", "TIMEOUT"].includes(
    String(status || "").toUpperCase()
  );
}

export function isRunFinalReplayFrame(frame: ConversationReplayFrame | undefined): boolean {
  if (!frame) {
    return false;
  }
  if (frame.eventType === "run.completed"
      || frame.eventType === "run.failed"
      || frame.eventType === "run.stopped") {
    return true;
  }
  if (!frame.itemId) {
    return false;
  }
  return isRunFinalTimelineItem({
    itemId: frame.itemId,
    kind: frame.kind,
    phase: frame.phase,
  });
}

/**
 * live SSE 的兼容判定：
 * - 已带 canonical itemId 时，只允许 run-final 成为终答；
 * - 旧后端帧没有 itemId 时返回 true，继续由 legacy 链路判定。
 */
export function isConclusionEligibleLiveFrame(frame: unknown): boolean {
  if (!frame || typeof frame !== "object") {
    return true;
  }
  const source = frame as Record<string, unknown>;
  const itemId = String(source.itemId || "");
  if (!itemId) {
    return true;
  }
  return isRunFinalTimelineItem({
    itemId,
    kind: typeof source.kind === "string"
      ? source.kind as AgentTimelineItem["kind"]
      : undefined,
    phase: typeof source.phase === "string"
      ? source.phase as AgentTimelineItem["phase"]
      : undefined,
  });
}
