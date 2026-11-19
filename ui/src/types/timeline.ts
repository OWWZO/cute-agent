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
