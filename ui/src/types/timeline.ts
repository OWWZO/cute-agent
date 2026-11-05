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
  payload?: TimelineItemPayload | null;
  errorMsg?: string | null;
}
