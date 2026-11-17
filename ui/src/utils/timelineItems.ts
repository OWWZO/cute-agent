import type {
  ConversationReplayFrame,
  ConversationRunReplay,
  ConversationRunSummary,
} from "@/services/agentConversation";
import type { AgentTimelineItem, TimelineEventType } from "@/types/timeline";
import { normalizeRunStatus } from "@/utils/runStatus";
import { TimelineStore } from "@/utils/timeline/timelineStore";

const ITEM_EVENT_TYPES = new Set<TimelineEventType>([
  "item.delta",
  "item.upserted",
  "item.completed",
]);

const RUN_EVENT_TYPES = new Set<TimelineEventType>([
  "run.completed",
  "run.failed",
  "run.stopped",
]);

const CONTROL_PACKAGE_TYPES: Partial<Record<TimelineEventType, string>> = {
  "stream.heartbeat": "heartbeat",
  "stream.follow_pending": "follow_pending",
  "stream.follow_idle": "follow_idle",
  "stream.closed": "stream_settle",
  "run.wake": "run_wake",
  "run.wake_failed": "run_wake_failed",
};

const CONTROL_EVENT_TYPES = new Set<TimelineEventType>(
  Object.keys(CONTROL_PACKAGE_TYPES) as TimelineEventType[],
);

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function readNestedTimelineField(
  value: unknown,
  key: "artifactId" | "toolInvocationId",
): number | string | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  const direct = value[key];
  if (direct !== null && direct !== undefined && String(direct).trim()) {
    return direct as number | string;
  }
  return readNestedTimelineField(value.resultMap, key);
}

function isPositiveNumericId(value: unknown): boolean {
  if (value === null || value === undefined || !String(value).trim()) {
    return false;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0;
}

/**
 * committed ARTIFACT 必须能解析到真实 artifact 主键和父 Tool。
 * 旧目录里的孤儿行只有 itemId/eventData，不是文件事实，不能生成 File 卡。
 * live transient file 帧仍允许显示，等待 Ledger 结算后再由 canonical 项替换。
 */
export function isRenderableTimelineItem(item: AgentTimelineItem): boolean {
  if (item.kind !== "ARTIFACT" || item.transient === true) {
    return true;
  }
  const itemId = String(item.itemId || "");
  if (/^artifact:[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(itemId)) {
    return false;
  }
  const artifactId =
    item.payload?.artifactId
    ?? readNestedTimelineField(item.payload?.eventData, "artifactId");
  if (!isPositiveNumericId(artifactId)) {
    return false;
  }
  const toolInvocationId =
    item.payload?.toolInvocationId
    ?? readNestedTimelineField(item.payload?.eventData, "toolInvocationId");
  if (!isPositiveNumericId(toolInvocationId)) {
    return false;
  }
  return String(item.parentItemId || "").startsWith("tool:");
}

export function isRunTerminal(eventType: TimelineEventType | undefined): boolean {
  return Boolean(eventType && RUN_EVENT_TYPES.has(eventType));
}

export function hasRenderableTimelineItems(items: AgentTimelineItem[] | undefined): boolean {
  return Array.isArray(items) && items.some((item) => Boolean(item.payload?.eventData));
}

export function isRunReplay(
  run: ConversationRunSummary | ConversationRunReplay,
): run is ConversationRunReplay {
  return "replayFrames" in run;
}

export function isTimelineEnvelope(value: unknown): value is AgentTimelineItem {
  if (!isRecord(value)) {
    return false;
  }
  const eventType = value.eventType;
  return typeof eventType === "string" && (
    ITEM_EVENT_TYPES.has(eventType as TimelineEventType)
    || RUN_EVENT_TYPES.has(eventType as TimelineEventType)
    || CONTROL_EVENT_TYPES.has(eventType as TimelineEventType)
  );
}

/**
 * 把语义时间线事件还原成现有渲染器消费的 canonical eventData。
 * 这里只做协议适配，不再维护 task/agent_stream/moreRounds 等业务状态机。
 */
export function timelineEnvelopeToLegacy(raw: Record<string, unknown>): Record<string, unknown> {
  if (!isTimelineEnvelope(raw)) {
    return raw;
  }
  const eventType = raw.eventType as TimelineEventType;
  const payload = isRecord(raw.payload) ? raw.payload : {};
  const eventData = isRecord(payload.eventData) ? payload.eventData : undefined;
  const controlPackageType = CONTROL_PACKAGE_TYPES[eventType];
  const runTerminal = isRunTerminal(eventType);
  const packageType = resolveLegacyPackageType(
    controlPackageType,
    runTerminal,
    eventData,
    raw.packageType,
  );
  const finished = resolveLegacyFinished(eventType, raw.finished);
  const status = resolveLegacyStatus(eventType, raw.status);

  return {
    ...raw,
    packageType,
    status,
    finished,
    errorMsg: runTerminal ? raw.errorMsg ?? "" : raw.errorMsg,
    resultMap: eventData
      ? {
        ...(isRecord(raw.resultMap) ? raw.resultMap : {}),
        eventData,
      }
      : raw.resultMap,
  };
}

function resolveLegacyPackageType(
  controlPackageType: string | undefined,
  runTerminal: boolean,
  eventData: Record<string, unknown> | undefined,
  rawPackageType: unknown,
): string {
  if (controlPackageType) {
    return controlPackageType;
  }
  if (runTerminal) {
    return "result";
  }
  return eventData ? "task" : String(rawPackageType || "task");
}

function resolveLegacyFinished(eventType: TimelineEventType, rawFinished: unknown): boolean {
  if (typeof rawFinished === "boolean") {
    return rawFinished;
  }
  return isRunTerminal(eventType);
}

function resolveLegacyStatus(eventType: TimelineEventType, rawStatus: unknown): string {
  if (eventType === "run.completed") {
    return "success";
  }
  if (eventType === "run.failed") {
    return "failed";
  }
  if (eventType === "run.stopped") {
    return "stopped";
  }
  return String(rawStatus || "running");
}

export function timelineItemsToReplayFrames(
  items: AgentTimelineItem[] | undefined,
): ConversationReplayFrame[] {
  if (!Array.isArray(items) || items.length === 0) {
    return [];
  }
  const frames: ConversationReplayFrame[] = [];
  for (const item of new TimelineStore(items).values()) {
    if (!isRenderableTimelineItem(item)) {
      continue;
    }
    const eventData = withTimelineResponseText(item);
    if (!eventData) {
      continue;
    }
    const itemStatus = item.itemStatus ?? item.status;
    frames.push({
      reqId: String(item.runId || eventData.resultMap?.requestId || ""),
      status: replayStatus(itemStatus),
      finished: isRunTerminal(item.eventType),
      itemId: item.itemId,
      parentItemId: item.parentItemId,
      agentId: item.agentId,
      kind: item.kind,
      phase: item.phase,
      itemStatus: item.itemStatus ?? (
        typeof item.status === "string"
          ? item.status.toUpperCase() as AgentTimelineItem["itemStatus"]
          : undefined
      ),
      orderKey: item.orderKey,
      subOrder: item.subOrder,
      eventType: item.eventType,
      resultMap: {
        agentType: "history",
        multiAgent: {},
        eventData,
      },
    });
  }
  return frames;
}

/**
 * live timeline 的 payload.eventData 可能是某一帧的快照；累计正文以
 * responseAll 为准。这里复制后再写入，避免污染 TimelineStore 中的事实对象。
 */
function withTimelineResponseText(item: AgentTimelineItem): MESSAGE.EventData | undefined {
  const source = item.payload?.eventData;
  if (!source) {
    return undefined;
  }
  const text = item.responseAll || (
    item.eventType === "item.delta" ? item.delta : undefined
  );
  if (!text) {
    return source;
  }
  const eventData = { ...source } as MESSAGE.EventData;
  const task = eventData.resultMap;
  if (task && typeof task === "object") {
    const messageType = String(task.messageType || "");
    eventData.resultMap = {
      ...task,
      ...(messageType === "llm_reasoning" ? { reasoningContent: text } : {}),
      ...(messageType === "tool_thought" ? { toolThought: text } : {}),
      ...(messageType === "agent_stream" || messageType === "result" || !messageType
        ? { result: text, taskSummary: text }
        : {}),
    };
  } else {
    (eventData as unknown as Record<string, unknown>).result = text;
  }
  return eventData;
}

function replayStatus(status: AgentTimelineItem["itemStatus"] | AgentTimelineItem["status"]): string {
  const normalizedStatus = String(status || "").toUpperCase() === "CANCELLED"
    ? "STOPPED"
    : status;
  switch (normalizeRunStatus(normalizedStatus)) {
    case "FAILED":
      return "failed";
    case "STOPPED":
      return "stopped";
    case "TIMEOUT":
      return "timeout";
    case "WAITING_INPUT":
      return "waiting_input";
    case "RUNNING":
      return "running";
    default:
      return "success";
  }
}

/**
 * timelineItems 是首选。目录兜底项可能没有 eventData，那是不可渲染的目录元数据，
 * 不能因为它存在就丢弃其他已经带完整 eventData 的时间线项。
 */
export function resolveRunReplayFrames(run: {
  timelineItems?: AgentTimelineItem[];
  replayFrames?: ConversationReplayFrame[];
}): ConversationReplayFrame[] {
  const items = Array.isArray(run.timelineItems) ? run.timelineItems : [];
  const timelineFrames = timelineItemsToReplayFrames(items);
  if (timelineFrames.length > 0) {
    return timelineFrames;
  }
  if (Array.isArray(run.replayFrames) && run.replayFrames.length > 0) {
    return run.replayFrames;
  }
  return timelineFrames;
}
