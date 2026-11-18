import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { conversationHistoryApi } from "@/services/agentConversation";
import type {
  ConversationHistoryPage,
  ConversationReplayFrame,
} from "@/services/agentConversation";
import type { AgentTimelineItem } from "@/types/timeline";
import { isRunTimelineTerminal } from "@/utils/timeline/timelineStore";
import { useRunTimeline } from "./useRunTimeline";
import {
  readSessionTimelineCache,
  writeSessionTimelineCache,
} from "./sessionTimelineCache";

export type SessionRunTimelineSource = {
  items: AgentTimelineItem[];
  /** 仅旧 run 没有 canonical timelineItems 时使用的只读兼容帧。 */
  replayFrames?: ConversationReplayFrame[];
  status: string;
  loaded: boolean;
};

export type SessionTimelineState = {
  ready: boolean;
  loading: boolean;
  error: string;
  history: ConversationHistoryPage | null;
  runs: Record<string, SessionRunTimelineSource>;
  activeRequestId: string;
  source: "idle" | "history" | "live";
  reconnectAttempts: number;
  reconnectExhausted: boolean;
};

export type SessionTimelineController = SessionTimelineState & {
  reload: (pendingRequestId?: string | null) => void;
  loadRun: (requestId: string) => Promise<void>;
  observeRun: (requestId: string) => void;
};

const IDLE_STATE: SessionTimelineState = {
  ready: false,
  loading: false,
  error: "",
  history: null,
  runs: {},
  activeRequestId: "",
  source: "idle",
  reconnectAttempts: 0,
  reconnectExhausted: false,
};

const CACHE_WRITE_INTERVAL_MS = 400;

function initialState(sessionId: string): SessionTimelineState {
  const cached = readSessionTimelineCache(sessionId);
  return cached
    ? {
        ...cached,
        loading: true,
      }
    : IDLE_STATE;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 会话级 canonical timeline 入口。
 *
 * 主 Agent 与子 Agent 都按同一个规则消费：
 * - 当前 run：run timeline stream；
 * - 历史 run：按需读取同一份 canonical timelineItems；
 * - 只有旧 run 没有 canonical items 时，才保留 replayFrames 只读兼容。
 */
export function useSessionTimeline(
  sessionId: string,
  enabled = true,
  requestIdOverride = ""
): SessionTimelineController {
  const [state, setState] = useState<SessionTimelineState>(() =>
    initialState(sessionId)
  );
  const [reloadSeq, setReloadSeq] = useState(0);
  const [activeRequestId, setActiveRequestId] = useState(
    () => requestIdOverride || readSessionTimelineCache(sessionId)?.activeRequestId || ""
  );
  const activeRequestIdRef = useRef(activeRequestId);
  const attemptedRunsRef = useRef(new Set<string>());
  const loadingRunsRef = useRef(new Set<string>());
  const refreshedTerminalRef = useRef("");
  const sessionIdRef = useRef(sessionId);
  const latestStateRef = useRef(state);
  const cacheSessionIdRef = useRef(sessionId);
  const cacheEnabledRef = useRef(enabled);
  const cacheFlushTimerRef = useRef<number | undefined>(undefined);

  const flushTimelineCache = useCallback(() => {
    if (cacheFlushTimerRef.current !== undefined) {
      window.clearTimeout(cacheFlushTimerRef.current);
      cacheFlushTimerRef.current = undefined;
    }
    if (!cacheEnabledRef.current) {
      return;
    }
    writeSessionTimelineCache(cacheSessionIdRef.current, latestStateRef.current);
  }, []);

  sessionIdRef.current = sessionId;
  activeRequestIdRef.current = requestIdOverride || activeRequestId;
  const liveRequestId = activeRequestIdRef.current;
  const live = useRunTimeline(
    liveRequestId,
    enabled && Boolean(liveRequestId),
    reloadSeq
  );

  useEffect(() => {
    if (requestIdOverride) {
      setActiveRequestId(requestIdOverride);
    }
  }, [requestIdOverride]);

  const fetchHistory = useCallback(async () => {
    if (!enabled || !sessionId) {
      return null;
    }
    const page = await conversationHistoryApi.getSessionDetail(sessionId, { limit: 50 });
    if (sessionIdRef.current !== sessionId) {
      return null;
    }
    if (!activeRequestIdRef.current) {
      const latest = String(page.latestRequestId || page.runs.at(-1)?.requestId || "");
      if (latest) {
        setActiveRequestId(latest);
      }
    }
    setState((prev) => ({
      ...prev,
      ready: true,
      history: page,
      loading: false,
      error: "",
      source: prev.source === "live" ? "live" : "history",
    }));
    return page;
  }, [enabled, sessionId]);

  useEffect(() => {
    if (!enabled || !sessionId) {
      setState(IDLE_STATE);
      attemptedRunsRef.current.clear();
      loadingRunsRef.current.clear();
      setActiveRequestId("");
      return;
    }
    attemptedRunsRef.current.clear();
    loadingRunsRef.current.clear();
    refreshedTerminalRef.current = "";
    const cached = readSessionTimelineCache(sessionId);
    if (cached) {
      setActiveRequestId(cached.activeRequestId || "");
      setState({
        ...cached,
        loading: true,
      });
    } else {
      setActiveRequestId("");
      setState({
        ...IDLE_STATE,
        activeRequestId: "",
        loading: true,
      });
    }
  }, [enabled, sessionId]);

  useEffect(() => {
    latestStateRef.current = state;
    cacheSessionIdRef.current = sessionId;
    cacheEnabledRef.current = enabled;
    if (!enabled || !sessionId || !state.ready) {
      return;
    }
    if (cacheFlushTimerRef.current !== undefined) {
      return;
    }
    cacheFlushTimerRef.current = window.setTimeout(() => {
      cacheFlushTimerRef.current = undefined;
      flushTimelineCache();
    }, CACHE_WRITE_INTERVAL_MS);
  }, [enabled, sessionId, state, flushTimelineCache]);

  // 切 session / 关闭订阅 / 卸载前，把最后一次状态同步写入缓存。
  useEffect(() => {
    return () => {
      flushTimelineCache();
    };
  }, [enabled, sessionId, flushTimelineCache]);

  useEffect(() => {
    if (!enabled || !sessionId) {
      return;
    }
    setState((prev) => ({ ...prev, loading: true, error: "" }));
    void fetchHistory().catch((error: unknown) => {
      if (sessionIdRef.current !== sessionId) {
        return;
      }
      setState((prev) => ({
        ...prev,
        ready: true,
        loading: false,
        error: errorMessage(error),
      }));
    });
  }, [enabled, sessionId, reloadSeq, fetchHistory]);

  useEffect(() => {
    if (!enabled || !liveRequestId) {
      return;
    }
    if (live.items.length === 0 && !live.status && !live.error) {
      return;
    }
    setState((prev) => {
      const previous = prev.runs[liveRequestId];
      const items = live.items.length ? live.items : previous?.items || [];
      return {
        ...prev,
        ready: true,
        loading: live.loading,
        error: live.error,
        source: live.items.length || live.status ? "live" : prev.source,
        reconnectAttempts: live.reconnectAttempts,
        reconnectExhausted: live.reconnectExhausted,
        runs: {
          ...prev.runs,
          [liveRequestId]: {
            items,
            replayFrames: items.length ? undefined : previous?.replayFrames,
            status: live.status || previous?.status || "",
            loaded: Boolean(previous?.loaded || items.length || live.status),
          },
        },
      };
    });
  }, [
    enabled,
    liveRequestId,
    live.items,
    live.status,
    live.loading,
    live.error,
    live.reconnectAttempts,
    live.reconnectExhausted,
  ]);

  useEffect(() => {
    if (!enabled || !liveRequestId || !isRunTimelineTerminal(live.status)) {
      return;
    }
    if (refreshedTerminalRef.current === `${liveRequestId}:${live.status}`) {
      return;
    }
    refreshedTerminalRef.current = `${liveRequestId}:${live.status}`;
    flushTimelineCache();
    void fetchHistory().catch(() => undefined).finally(flushTimelineCache);
  }, [
    enabled,
    liveRequestId,
    live.status,
    fetchHistory,
    flushTimelineCache,
  ]);

  const loadRun = useCallback(async (requestId: string) => {
    const id = String(requestId || "").trim();
    if (!enabled
        || !sessionId
        || !id
        || attemptedRunsRef.current.has(id)
        || loadingRunsRef.current.has(id)) {
      return;
    }
    loadingRunsRef.current.add(id);
    setState((prev) => ({ ...prev, loading: true, error: "" }));
    try {
      const replay = await conversationHistoryApi.getRunReplay(id);
      if (sessionIdRef.current !== sessionId) {
        return;
      }
      attemptedRunsRef.current.add(id);
      setState((prev) => ({
        ...prev,
        loading: false,
        runs: {
          ...prev.runs,
          [id]: {
            items: replay.timelineItems || [],
            replayFrames: replay.timelineItems?.length ? undefined : replay.replayFrames || [],
            status: replay.status || "",
            loaded: true,
          },
        },
      }));
    } catch (error) {
      if (sessionIdRef.current !== sessionId) {
        return;
      }
      setState((prev) => ({
        ...prev,
        loading: false,
        error: errorMessage(error),
      }));
    } finally {
      loadingRunsRef.current.delete(id);
    }
  }, [enabled, sessionId]);

  const reload = useCallback((pendingRequestId?: string | null) => {
    const nextRequestId = String(pendingRequestId || "").trim();
    flushTimelineCache();
    if (nextRequestId) {
      setActiveRequestId(nextRequestId);
    }
    setReloadSeq((value) => value + 1);
  }, [flushTimelineCache]);

  const observeRun = useCallback((requestId: string) => {
    const id = String(requestId || "").trim();
    if (id) {
      setActiveRequestId(id);
    }
  }, []);

  return useMemo(() => ({
    ...state,
    activeRequestId: liveRequestId,
    reconnectAttempts: live.reconnectAttempts || state.reconnectAttempts,
    reconnectExhausted: live.reconnectExhausted || state.reconnectExhausted,
    reload,
    loadRun,
    observeRun,
  }), [
    state,
    liveRequestId,
    live.reconnectAttempts,
    live.reconnectExhausted,
    reload,
    loadRun,
    observeRun,
  ]);
}

export default useSessionTimeline;
