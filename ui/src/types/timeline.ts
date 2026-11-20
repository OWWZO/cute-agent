export type TimelineEventType =
  | "item.delta"
  | "item.upserted"
  | "item.completed"
  | "run.completed"
  | "run.failed"
  | "run.stopped"
  | "run.wake"
  | "run.wake_failed"
  | "stream.heartbeat"
  | "stream.follow_pending"
  | "stream.follow_idle"
  | "stream.closed";

/**
 * TimelinePatch v1 的唯一写入动作。
 *
 * 旧 SSE/replay 仍由兼容 adapter 转成 AgentTimelineItem；新协议必须显式声明
 * operation，前端不再根据文本前后缀猜“追加还是覆盖”。
 */
export type TimelinePatchOperation =
  | "upsert"
  | "append"
  | "replace"
  | "status"
  | "remove";

export type TimelineItemKind =
  | "ASSISTANT_MESSAGE"
  | "REASONING"
  | "TOOL_CALL"
  | "ARTIFACT"
  | "PLAN"
  | "HITL"
  | "SUBAGENT"
  | "SYSTEM_NOTICE";

export type TimelineItemStatus =
  | "RUNNING"
  | "WAITING_INPUT"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export interface TimelineItemPayload {
  eventData?: MESSAGE.EventData;
  /** 同一 live timeline item 下按阶段合并后的 DeepSearch 快照。 */
  eventDataHistory?: MESSAGE.EventData[];
  artifactId?: number | null;
  toolInvocationId?: number | null;
  [key: string]: unknown;
}

export interface AgentTimelineItem {
  protocolVersion?: string;
  /** 新 canonical patch 的写入动作；旧历史项没有该字段。 */
  operation?: TimelinePatchOperation;
  eventType?: TimelineEventType;
  eventId?: string;
  runId?: string;
  sessionId?: string;
  seq?: number;
  /** live SSE 传输游标；不参与 committed timeline 排序。 */
  eventSeq?: number;
  itemId?: string;
  parentItemId?: string | null;
  agentId?: string | null;
  kind?: TimelineItemKind;
  phase?: "INTERMEDIATE" | "SUMMARY" | "FINAL";
  itemStatus?: TimelineItemStatus;
  /**
   * history item 使用 TimelineItemStatus；live SSE 信封仍沿用传输层
   * running/success/failed/stopped，消费时由 itemStatus 优先覆盖。
   */
  status?: TimelineItemStatus | "running" | "success" | "failed" | "stopped" | "timeout";
  orderKey?: number;
  subOrder?: number;
  delta?: string | null;
  /** 当前帧新增文本。 */
  response?: string | null;
  /** 截至当前帧的累计文本。 */
  responseAll?: string | null;
  payload?: TimelineItemPayload | null;
  errorMsg?: string | null;
  /** live 展示帧为 true；committed snapshot 永远为 false。 */
  transient?: boolean;
}

/**
 * 实时 SSE 与历史 replay 共用的时间线写入协议。
 *
 * `itemId` 是唯一 React 身份；`eventId` 用于幂等去重；`seq` 用于阻止旧帧
 * 覆盖新帧。`contentMode`/`contentOffset` 只描述正文内容，不再让 UI 层猜
 * append 与 snapshot。
 */
export interface TimelinePatch {
  protocolVersion: "timeline.v1";
  runId: string;
  sessionId: string;
  eventId: string;
  seq: number;
  operation: TimelinePatchOperation;
  itemId: string;
  parentItemId?: string | null;
  kind: TimelineItemKind;
  phase?: AgentTimelineItem["phase"];
  status?: TimelineItemStatus;
  contentMode?: "append" | "replace" | "snapshot";
  contentOffset?: number;
  /** append 模式新增的正文；replace/snapshot 模式为完整正文。 */
  delta?: string | null;
  /** 截至当前 patch 的累计正文。 */
  responseAll?: string | null;
  payload?: TimelineItemPayload;
  artifactIds?: string[];
  startedAt?: string;
  finishedAt?: string;
  error?: string | null;
}
