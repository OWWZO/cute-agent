import { useEffect, useRef, useState } from "react";
import { buildRunTimelineStreamUrl } from "@/services/agentRun";
import type { AgentTimelineItem } from "@/types/timeline";
import querySSE from "@/utils/querySSE";
import { parseAgentAnswer } from "@/utils/sseParsers";
import { isRunTerminal } from "@/utils/timelineItems";
import {
  isRunFinalStatus,
  isRunTimelineTerminal,
  TimelineStore,
} from "@/utils/timeline/timelineStore";

export type RunTimelineState = {
  items: AgentTimelineItem[];
  status: string;
  loading: boolean;
  error: string;
  reconnectAttempts: number;
  reconnectExhausted: boolean;
};

type InternalRunTimelineState = RunTimelineState & {
  requestId: string;
};

const IDLE_STATE: InternalRunTimelineState = {
  requestId: "",
  items: [],
  status: "",
  loading: false,
  error: "",
  reconnectAttempts: 0,
  reconnectExhausted: false,
};

const RUN_TIMELINE_RECONNECT_CAP = 20;

/**
 * 历史 run 的 canonical items 已由历史接口带回，不需要再开 timeline 流。
 * 运行中的 run 仍必须订阅，避免工具卡和正文停留在首帧快照。
 */
export function shouldObserveRunTimeline(
  requestId: string,
  status?: string | null,
  observedLive?: boolean
): boolean {
  if (!requestId) {
    return false;
  }
  if (!isRunTimelineTerminal(status || "")) {
    return true;
  }
  // 聊天状态可能先于 run-final 到达。已经观察过 live 流时继续等时间线
  // 自己收口，避免在最后一个工具项之后丢掉终答。
  return Boolean(observedLive) && isRunFinalStatus(status || "");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

/**
 * 把 run 端点发来的 canonical envelope 收窄成前端 TimelineItem。
 * 控制事件没有 itemId，只更新 run 状态，不进入时间线存储。
 */
export function toTimelineItem(frame: unknown): AgentTimelineItem | undefined {
  if (!isRecord(frame) || !frame.eventType || !frame.itemId) {
    return undefined;
  }
  const resultMap = isRecord(frame.resultMap) ? frame.resultMap : {};
  const rawPayload = isRecord(frame.payload) ? frame.payload : {};
  const eventData = isRecord(rawPayload.eventData)
    ? rawPayload.eventData
    : isRecord(resultMap.eventData)
      ? resultMap.eventData
      : undefined;
  return {
    protocolVersion: typeof frame.protocolVersion === "string"
      ? frame.protocolVersion
      : undefined,
    eventType: frame.eventType as AgentTimelineItem["eventType"],
    eventId: typeof frame.eventId === "string" ? frame.eventId : undefined,
    runId: typeof frame.runId === "string" ? frame.runId : undefined,
    sessionId: typeof frame.sessionId === "string" ? frame.sessionId : undefined,
    seq: Number(frame.seq || 0),
    eventSeq: Number(frame.eventSeq || 0),
    itemId: String(frame.itemId),
    parentItemId: typeof frame.parentItemId === "string" ? frame.parentItemId : null,
    agentId: typeof frame.agentId === "string" ? frame.agentId : null,
    kind: frame.kind as AgentTimelineItem["kind"],
    phase: frame.phase as AgentTimelineItem["phase"],
    itemStatus: frame.itemStatus as AgentTimelineItem["itemStatus"],
    orderKey: Number(frame.orderKey || 0),
    subOrder: Number(frame.subOrder || 0),
    delta: typeof frame.delta === "string" ? frame.delta : null,
    response: typeof frame.response === "string" ? frame.response : null,
    responseAll: typeof frame.responseAll === "string" ? frame.responseAll : null,
    payload: {
      ...rawPayload,
      ...(eventData ? { eventData: eventData as unknown as MESSAGE.EventData } : {}),
    },
    errorMsg: typeof frame.errorMsg === "string" ? frame.errorMsg : null,
    transient: frame.transient === true,
  };
}

/**
 * run-final item 也使用 run.completed 事件类型；只有不带 itemId 的帧才是
 * 表示流结束的控制帧。两者必须先按身份区分，否则快照里的终答会被吞掉。
 */
export function isTerminalRunControl(frame: unknown): boolean {
  if (!isRecord(frame) || String(frame.itemId || "").trim()) {
    return false;
  }
  return isRunTerminal(frame.eventType as AgentTimelineItem["eventType"]);
}

export function isWaitingInputIdleControl(frame: unknown): boolean {
  return isRecord(frame)
    && frame.eventType === "stream.follow_idle"
    && String(frame.status || "").toUpperCase() === "WAITING_INPUT";
}

export function useRunTimeline(
  requestId: string,
  enabled = true,
  revision: string | number = 0
): RunTimelineState {
  const [state, setState] = useState<InternalRunTimelineState>(IDLE_STATE);
  const storeRef = useRef(new TimelineStore());
  const seqRef = useRef(0);
  const activeRequestIdRef = useRef("");

  useEffect(() => {
    const requestChanged = activeRequestIdRef.current !== requestId;
    activeRequestIdRef.current = requestId;
    if (requestChanged) {
      storeRef.current = new TimelineStore();
      seqRef.current = 0;
    }
    if (!enabled || !requestId) {
      // 同一 run 从 RUNNING 变为终态时保留已接收的 canonical items。
      // 直接清空会让可见区在完成瞬间回退到控制流快照，造成工具卡闪动。
      if (requestChanged || !requestId) {
        setState(IDLE_STATE);
      }
      return;
    }

    const controller = new AbortController();
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let terminal = false;
    let reconnectAttempts = 0;
    setState({ ...IDLE_STATE, requestId, loading: true });
    const open = () => {
      if (controller.signal.aborted || terminal) {
        return;
      }
      querySSE(
        {
          method: "GET",
          body: null,
          signal: controller.signal,
          retryOnError: false,
          parser: parseAgentAnswer,
          handleMessage: (frame) => {
            reconnectAttempts = 0;
            const source = frame as unknown as Record<string, unknown>;
            const seq = Number(source.eventSeq || 0);
            if (seq > seqRef.current) {
              seqRef.current = seq;
            }
            const eventType = String(source.eventType || "");
            const item = toTimelineItem(frame);
            if (item) {
              storeRef.current.upsertMany([item]);
              setState((prev) => ({
                ...prev,
                items: storeRef.current.values(),
                loading: true,
                reconnectAttempts: 0,
                reconnectExhausted: false,
              }));
              return;
            }
            if (isTerminalRunControl(frame)) {
              terminal = true;
              setState((prev) => ({
                ...prev,
                status: eventType === "run.completed"
                  ? "SUCCESS"
                  : eventType === "run.stopped"
                    ? "STOPPED"
                    : "FAILED",
                loading: false,
                reconnectAttempts: 0,
                reconnectExhausted: false,
              }));
              return;
            }
            if (isWaitingInputIdleControl(frame)) {
              // HITL 停驻态没有活跃投影，不应反复重连；恢复输入时外层会递增 revision 重新订阅。
              terminal = true;
              setState((prev) => ({
                ...prev,
                status: "WAITING_INPUT",
                loading: false,
                reconnectAttempts: 0,
                reconnectExhausted: false,
              }));
              return;
            }
            if (eventType.startsWith("run.")) {
              return;
            }
          },
          handleError: (error) => {
            if (!controller.signal.aborted) {
              setState((prev) => ({
                ...prev,
                error: error instanceof Error ? error.message : String(error),
              }));
            }
          },
          handleClose: () => {
            if (controller.signal.aborted || terminal) {
              setState((prev) => ({ ...prev, loading: false }));
              return;
            }
            reconnectAttempts += 1;
            if (reconnectAttempts > RUN_TIMELINE_RECONNECT_CAP) {
              setState((prev) => ({
                ...prev,
                loading: false,
                reconnectAttempts,
                reconnectExhausted: true,
              }));
              return;
            }
            const delay = Math.min(800 * 2 ** (reconnectAttempts - 1), 15_000);
            setState((prev) => ({
              ...prev,
              loading: true,
              reconnectAttempts,
              reconnectExhausted: false,
            }));
            reconnectTimer = setTimeout(open, delay);
          },
        },
        buildRunTimelineStreamUrl({ requestId, afterSeq: seqRef.current })
      );
    };
    open();
    return () => {
      controller.abort();
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
      }
    };
  }, [enabled, requestId, revision]);

  if (!requestId || state.requestId !== requestId) {
    return {
      ...IDLE_STATE,
      loading: enabled && Boolean(requestId),
    };
  }
  return state;
}

export default useRunTimeline;
