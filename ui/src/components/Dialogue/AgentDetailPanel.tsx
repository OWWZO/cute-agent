import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { X } from "lucide-react";
import { resolveSubAgentDisplay } from "@/utils/chat/subagent";
import { buildSessionTimelineRounds } from "@/utils/chat/sessionTimelineChat";
import { groupChatsIntoTurns, mergeTurnChats } from "@/lib/turnGroups";
import { projectAgentMember } from "@/utils/chat/agentRuntimeProjector";
import { useSessionTimeline } from "@/hooks/useSessionTimeline";
import {
  formatAgentElapsed,
  resolveAgentPhaseLabel,
  resolveAgentPhaseTone,
} from "./agentProgressGroups";
import { cn } from "@/lib/utils";
import { StatusDot } from "./tools/StatusDot";
import Dialogue from "./index";
import SubAgentSessionComposer, {
  type SubAgentSubmission,
} from "./SubAgentSessionComposer";
import {
  type WorkspaceTabScrollSnapshot,
  readWorkspaceTabScroll,
  writeWorkspaceTabScroll,
} from "@/components/ActionView/workspaceTabScrollCache";
import {
  createScrollSnapshot,
  resolveRestoreScrollTop,
} from "@/components/ActionView/workspaceTabScrollRestore";

/**
 * 子会话真实详情面板开关（前端侧，与后端 autobots.agent.subagent-isolated-session 成对）。
 * <p>
 * 默认开启并只消费真实子会话 canonical timeline；显式设为 0 时仅作为短期回滚。
 * 不再回退到第二套合成投影。
 */
export const SUBAGENT_CHILD_SESSION_ENABLED =
  (import.meta.env?.VITE_SUBAGENT_CHILD_SESSION as string | undefined) !== "0";

/** 子会话一次挂载的轮数；更早轮次按需展开，避免长会话把所有 Dialogue 同时挂上。 */
const CHILD_SESSION_TURN_PAGE_SIZE = 8;
const SCROLL_ANCHOR_IDLE_MS = 100;
const SCROLL_CORRECTION_WINDOW_MS = 400;
const useIsomorphicLayoutEffect =
  typeof window === "undefined" ? useEffect : useLayoutEffect;

type AgentDetailPanelProps = {
  tool: CHAT.Task;
  chat: CHAT.ChatItem;
  onClose?: () => void;
  /**
   * 作为右侧工作区 tab 内容渲染（而非独占面板）。
   *
   * tab 条本身已经有标题与关闭按钮，所以嵌入态不再画自己的关闭按钮，
   * 避免同一个面板出现两个 X。
   */
  embedded?: boolean;
  changeActiveChat?: CHAT.OpenTaskHandler;
  changeFile?: CHAT.OpenFileHandler;
  sessionArtifactFiles?: CHAT.TFile[];
  changePlan?: () => void;
  onOpenToolDiff?: (task: CHAT.Task, chat: CHAT.ChatItem, fileIndex?: number) => void;
  onOpenAgent?: (task: CHAT.Task, chat: CHAT.ChatItem) => void;
  onOpenWorkspaceFiles?: () => void;
  /** ActionView 工作区的阅读现场身份；不传时保持独立面板原行为。 */
  scrollRestoreKey?: string;
  scrollScope?: string;
};

export const AgentDetailPanel = memo(function AgentDetailPanel({
  tool,
  chat,
  onClose,
  embedded = false,
  changeActiveChat,
  changePlan,
  onOpenToolDiff,
  onOpenAgent,
  changeFile,
  sessionArtifactFiles,
  onOpenWorkspaceFiles,
  scrollRestoreKey,
  scrollScope,
}: AgentDetailPanelProps) {
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const [initialScrollSnapshot] = useState(() =>
    readWorkspaceTabScroll(scrollScope, scrollRestoreKey)
  );
  const restoredRef = useRef(!initialScrollSnapshot);
  const processOpenByAnchorIdRef = useRef<Record<string, boolean>>({
    ...(initialScrollSnapshot?.processOpenByAnchorId || {}),
  });
  const scrollRafRef = useRef(0);
  const anchorTimerRef = useRef<number | undefined>(undefined);
  const processSnapshotRafRef = useRef(0);
  const processSnapshotPendingRef = useRef(false);
  const restoreObserverRef = useRef<ResizeObserver | null>(null);
  const restoreObserverTimerRef = useRef<number | undefined>(undefined);
  const lastRestoreScrollTopRef = useRef<number | null>(null);
  const sub = useMemo(() => resolveSubAgentDisplay(tool), [tool]);
  const member = useMemo(() => projectAgentMember(tool), [tool]);
  const nested = tool.children || [];
  const duration = formatAgentElapsed(sub);
  const phaseLabel = resolveAgentPhaseLabel(member?.phase, sub.status);
  const phaseTone = resolveAgentPhaseTone(member?.phase, sub.status);

  // 子会话真实详情：只有开关开启且父卡确实带 childSessionId 时才订阅。
  // 不具备 childSessionId（旧会话 / 未落库成功）时只展示空态，不伪造轨迹。
  const childSessionId = SUBAGENT_CHILD_SESSION_ENABLED ? sub.childSessionId : "";
  const childSession = useSessionTimeline(childSessionId, Boolean(childSessionId));
  const useRealChildSession = Boolean(childSessionId);

  // 数据源：真实子会话继续由会话级渲染链路消费；本面板在真实模式下
  // 以「历史已就绪 + 实时帧已到达」作为渲染准入，避免打开瞬间空白。
  const [readySessionId, setReadySessionId] = useState(() =>
    childSession.ready ? childSessionId : ""
  );
  const realSessionReady =
    Boolean(childSessionId) && readySessionId === childSessionId;
  useEffect(() => {
    if (!useRealChildSession) {
      setReadySessionId("");
      return;
    }
    if (childSession.ready && readySessionId !== childSessionId) {
      setReadySessionId(childSessionId);
    }
  }, [
    useRealChildSession,
    childSession.ready,
    childSessionId,
    readySessionId,
  ]);

  // 真实子会话：history / 按 run 的回放 / 实时帧 → 走与主会话同构的渲染链路。
  // 每轮一个 ChatItem，先切 turn 再合并，最后交给同一个 `Dialogue`。
  const [pendingRun, setPendingRun] = useState<{
    requestId: string;
    query: string;
  } | null>(null);

  const childSessionRounds = useMemo(() => {
    if (!useRealChildSession || !realSessionReady) {
      return [];
    }
    return buildSessionTimelineRounds({
      sessionId: childSessionId,
      history: childSession.history,
      sources: childSession.runs,
      pendingRun,
      fallbackQuery: sub.prompt || sub.description,
    });
  }, [
    useRealChildSession,
    realSessionReady,
    childSessionId,
    childSession.history,
    childSession.runs,
    pendingRun,
    sub.prompt,
    sub.description,
  ]);

  const childSessionTurns = useMemo(
    () =>
      groupChatsIntoTurns(childSessionRounds).map((turn) => ({
        key: turn.key,
        chat: mergeTurnChats(turn),
      })),
    [childSessionRounds]
  );
  const [visibleTurnCount, setVisibleTurnCount] = useState(() => {
    return {
      sessionId: childSessionId,
      count: Math.max(
        CHILD_SESSION_TURN_PAGE_SIZE,
        initialScrollSnapshot?.visibleTurnCount || CHILD_SESSION_TURN_PAGE_SIZE
      ),
    };
  });
  const visibleCount =
    visibleTurnCount.sessionId === childSessionId
      ? visibleTurnCount.count
      : CHILD_SESSION_TURN_PAGE_SIZE;
  const visibleChildSessionTurns = childSessionTurns.slice(-visibleCount);
  const hiddenChildSessionTurnCount =
    childSessionTurns.length - visibleChildSessionTurns.length;
  const reloadChildSession = childSession.reload;
  const observeChildRun = childSession.observeRun;
  const childRunCount = Object.keys(childSession.runs).length;
  const handleSubmitted = useCallback(
    (submitted: SubAgentSubmission) => {
      if (submitted.kind === "guidance") {
        setPendingRun(null);
        reloadChildSession(submitted.activeRequestId);
        if (submitted.activeRequestId) {
          observeChildRun(submitted.activeRequestId);
        }
        return;
      }
      // 乐观轮：新 run 还没落库，先让用户看到自己的提问与「工作中 X」，
      // 历史返回后按 requestId 去重，由真实 run 顶替。
      setPendingRun(submitted);
      reloadChildSession(submitted.requestId);
    },
    [observeChildRun, reloadChildSession]
  );

  const showEmpty =
    childSessionTurns.length === 0 &&
    (realSessionReady || Boolean(childSession.error) || !useRealChildSession);

  const handleChangeActiveChat = useCallback(
    (task: CHAT.Task) => {
      changeActiveChat?.(task, chat);
    },
    [changeActiveChat, chat]
  );
  const handleOpenToolDiff = useCallback(
    (task: CHAT.Task, _chat?: CHAT.ChatItem, fileIndex?: number) => {
      onOpenToolDiff?.(task, chat, fileIndex);
    },
    [onOpenToolDiff, chat]
  );
  const handleOpenAgent = useCallback(
    (task: CHAT.Task) => {
      onOpenAgent?.(task, chat);
    },
    [onOpenAgent, chat]
  );
  const handleChangeFile = useCallback(
    (file: CHAT.TFile) => {
      changeFile?.(file, chat);
    },
    [changeFile, chat]
  );

  const rememberScroll = useCallback((includeAnchor = true) => {
    const el = bodyRef.current;
    if (!el || !scrollRestoreKey || !restoredRef.current) {
      return;
    }
    writeWorkspaceTabScroll(
      scrollScope,
      scrollRestoreKey,
      createScrollSnapshot(el, visibleCount, processOpenByAnchorIdRef.current, {
        includeAnchor,
        includeProcessState: includeAnchor,
      })
    );
  }, [scrollRestoreKey, scrollScope, visibleCount]);
  const rememberScrollRef = useRef(rememberScroll);
  rememberScrollRef.current = rememberScroll;
  const scheduleProcessSnapshot = useCallback(() => {
    if (
      !scrollRestoreKey ||
      !restoredRef.current ||
      processSnapshotRafRef.current
    ) {
      return;
    }
    processSnapshotPendingRef.current = true;
    processSnapshotRafRef.current = requestAnimationFrame(() => {
      processSnapshotRafRef.current = 0;
      processSnapshotPendingRef.current = false;
      rememberScrollRef.current();
    });
  }, [scrollRestoreKey]);
  const scheduleProcessSnapshotRef = useRef(scheduleProcessSnapshot);
  scheduleProcessSnapshotRef.current = scheduleProcessSnapshot;

  const stopRestoreCorrection = useCallback(() => {
    restoreObserverRef.current?.disconnect();
    restoreObserverRef.current = null;
    if (restoreObserverTimerRef.current !== undefined) {
      window.clearTimeout(restoreObserverTimerRef.current);
      restoreObserverTimerRef.current = undefined;
    }
  }, []);

  const applyRestoreScroll = useCallback(
    (el: HTMLDivElement, snapshot: WorkspaceTabScrollSnapshot) => {
      el.scrollTop = snapshot.atBottom
        ? el.scrollHeight
        : resolveRestoreScrollTop(el, snapshot);
      lastRestoreScrollTopRef.current = el.scrollTop;
    },
    []
  );

  const startRestoreCorrection = useCallback(
    (el: HTMLDivElement, snapshot: WorkspaceTabScrollSnapshot) => {
      stopRestoreCorrection();
      if (typeof ResizeObserver === "undefined") {
        return;
      }
      const observer = new ResizeObserver(() => {
        applyRestoreScroll(el, snapshot);
      });
      const observed = Array.from(el.children);
      if (observed.length) {
        observed.forEach((child) => observer.observe(child));
      } else {
        observer.observe(el);
      }
      restoreObserverRef.current = observer;
      restoreObserverTimerRef.current = window.setTimeout(
        stopRestoreCorrection,
        SCROLL_CORRECTION_WINDOW_MS
      );
    },
    [applyRestoreScroll, stopRestoreCorrection]
  );

  const processOpenHandlersRef = useRef(
    new Map<string, (open: boolean) => void>()
  );
  const processOpenHandlerFor = (anchorId: string) => {
    const existing = processOpenHandlersRef.current.get(anchorId);
    if (existing) {
      return existing;
    }
    const handler = (open: boolean) => {
      processOpenByAnchorIdRef.current[anchorId] = open;
      scheduleProcessSnapshotRef.current();
    };
    processOpenHandlersRef.current.set(anchorId, handler);
    return handler;
  };

  const handleScroll = useCallback(() => {
    if (!scrollRestoreKey || !restoredRef.current) {
      return;
    }
    const el = bodyRef.current;
    if (
      el &&
      restoreObserverRef.current &&
      lastRestoreScrollTopRef.current !== null &&
      Math.abs(el.scrollTop - lastRestoreScrollTopRef.current) > 1
    ) {
      stopRestoreCorrection();
    }
    if (!scrollRafRef.current) {
      scrollRafRef.current = requestAnimationFrame(() => {
        scrollRafRef.current = 0;
        // 过程区状态变化会安排一次完整快照；避免轻量快照把它覆盖掉。
        if (!processSnapshotPendingRef.current) {
          rememberScrollRef.current(false);
        }
      });
    }
    // 已经有一帧待处理时仍要重置空闲计时器，滚动结束后才能补算锚点。
    if (anchorTimerRef.current !== undefined) {
      window.clearTimeout(anchorTimerRef.current);
    }
    anchorTimerRef.current = window.setTimeout(() => {
      anchorTimerRef.current = undefined;
      rememberScrollRef.current();
    }, SCROLL_ANCHOR_IDLE_MS);
  }, [scrollRestoreKey, stopRestoreCorrection]);

  useEffect(() => {
    return () => {
      if (scrollRafRef.current) {
        cancelAnimationFrame(scrollRafRef.current);
        scrollRafRef.current = 0;
      }
      if (anchorTimerRef.current !== undefined) {
        window.clearTimeout(anchorTimerRef.current);
        anchorTimerRef.current = undefined;
      }
      if (processSnapshotRafRef.current) {
        cancelAnimationFrame(processSnapshotRafRef.current);
        processSnapshotRafRef.current = 0;
      }
      processSnapshotPendingRef.current = false;
      stopRestoreCorrection();
      rememberScroll();
    };
  }, [rememberScroll, stopRestoreCorrection]);

  useIsomorphicLayoutEffect(() => {
    if (
      !scrollRestoreKey ||
      restoredRef.current ||
      !initialScrollSnapshot ||
      !realSessionReady ||
      childSessionTurns.length === 0
    ) {
      return;
    }
    const el = bodyRef.current;
    if (!el || el.scrollHeight <= el.clientHeight) {
      if (initialScrollSnapshot?.atBottom) {
        restoredRef.current = true;
      }
      return;
    }
    // 内容已经挂载后再恢复，并且用 layout effect 在下一帧绘制前完成定位。
    applyRestoreScroll(el, initialScrollSnapshot);
    restoredRef.current = true;
    startRestoreCorrection(el, initialScrollSnapshot);
  }, [
    scrollRestoreKey,
    realSessionReady,
    childSessionTurns.length,
    initialScrollSnapshot,
    applyRestoreScroll,
    startRestoreCorrection,
  ]);

  useEffect(() => {
    const el = bodyRef.current;
    if (!el) return;
    const frame = requestAnimationFrame(() => {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 32;
      const saved = readWorkspaceTabScroll(scrollScope, scrollRestoreKey);
      if (atBottom && (!saved || saved.atBottom)) {
        el.scrollTop = el.scrollHeight;
      }
    });
    return () => cancelAnimationFrame(frame);
  }, [
    nested.length,
    sub.content,
    sub.status,
    sub.liveText,
    childRunCount,
    childSessionTurns.length,
    scrollRestoreKey,
    scrollScope,
  ]);

  return (
    <div className="kimi-detail-panel">
      <div className={cn("kimi-detail-panel-head", embedded && "pt-3")}>
        <div className="min-w-0 flex-1">
          <div className="text-ui-sm text-[var(--color-text-faint)]">子智能体</div>
          <div className="truncate text-ui-base font-medium text-[var(--color-text)]">
            {sub.description || sub.subagentType || "Agent"}
          </div>
          <div className="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-ui-sm text-[var(--color-text-muted)]">
            <span
              className={cn(
                "kimi-agent-phase-badge",
                phaseTone === "running" && "is-running",
                phaseTone === "ok" && "is-ok",
                phaseTone === "error" && "is-error"
              )}
            >
              {phaseTone === "running" ? <StatusDot status="running" /> : null}
              {phaseLabel}
            </span>
            {sub.subagentType ? <span>{sub.subagentType}</span> : null}
            {nested.length > 0 ? <span>{nested.length} tools</span> : null}
            {duration ? <span>{duration}</span> : null}
            {sub.runInBackground ? (
              <span className="rounded border border-[var(--color-line)] px-1.5 py-0.5 text-ui-xs uppercase tracking-wide">
                background
              </span>
            ) : null}
            {useRealChildSession ? (
              <span
                className="rounded border border-[var(--color-line)] px-1.5 py-0.5 text-ui-xs uppercase tracking-wide"
                title={`子会话 ${childSessionId}`}
              >
                session
              </span>
            ) : null}
          </div>
        </div>
        {embedded ? null : (
          <button
            type="button"
            className="flex h-7 w-7 items-center justify-center rounded-full text-[var(--color-text-muted)] transition-colors hover:bg-[var(--color-surface-sunken)] hover:text-[var(--color-text)]"
            aria-label="关闭"
            onClick={onClose}
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>
      <div
        ref={bodyRef}
        onScroll={handleScroll}
        className="kimi-detail-panel-body px-3 py-2"
      >
        {sub.errorMsg ? (
          <div className="mb-2 rounded-lg border border-[var(--color-danger-soft)] bg-[var(--color-danger-soft)] px-3 py-2 text-ui-caption text-[var(--color-danger)]">
            {sub.errorMsg}
          </div>
        ) : null}

        {childSession.error ? (
          <div className="mb-2 rounded-lg border border-[var(--color-warning-bd)] bg-[var(--color-warning-soft)] px-3 py-2 text-ui-caption text-[var(--color-warning)]">
            子会话读取失败：{childSession.error}
          </div>
        ) : null}

        {/* 重订熔断：保留已收到的进度，而不是让它看起来还在加载 */}
        {childSession.reconnectExhausted ? (
          <div className="mb-2 rounded-lg border border-[var(--color-warning-bd)] bg-[var(--color-warning-soft)] px-3 py-2 text-ui-caption text-[var(--color-warning)]">
            子会话流已断开且重连失败，当前显示最后一次收到的进度。切换到其他标签页或刷新可重新加载。
          </div>
        ) : null}

        {/* 真实子会话模式下 history 拉回前显示骨架，不塞假轨迹（计划书 §5.4） */}
        {useRealChildSession && !realSessionReady && !childSession.error ? (
          <div className="kimi-detail-panel-empty">加载子会话…</div>
        ) : null}

        {/* 子会话按轮窗口化；流式 live run 常驻尾部，避免子会话长期运行时历史 Dialogue 全挂载。 */}
        {useRealChildSession && childSessionTurns.length ? (
          <div className="flex flex-col gap-2">
            {hiddenChildSessionTurnCount > 0 ? (
              <button
                type="button"
                className="self-center rounded-full px-3 py-1.5 text-ui-xs text-[var(--color-text-subtle)] transition-colors hover:bg-[var(--color-surface-sunken)] hover:text-[var(--color-text)]"
                onClick={() =>
                  setVisibleTurnCount({
                    sessionId: childSessionId,
                    count: visibleCount + CHILD_SESSION_TURN_PAGE_SIZE,
                  })
                }
              >
                显示更早的 {hiddenChildSessionTurnCount} 轮
              </button>
            ) : null}
            {visibleChildSessionTurns.map((turn) => {
              const anchorId = `turn:${turn.key || childSessionId}`;
              return (
                <Dialogue
                  key={turn.key || `child-session-${childSessionId}`}
                  scrollAnchorId={anchorId}
                  initialProcessOpen={
                    processOpenByAnchorIdRef.current[anchorId]
                  }
                  onProcessOpenChange={processOpenHandlerFor(anchorId)}
                  chat={turn.chat}
                  deepThink={false}
                  sessionArtifactFiles={sessionArtifactFiles}
                  changeTask={handleChangeActiveChat}
                  changeFile={handleChangeFile}
                  changePlan={changePlan}
                  onOpenToolDiff={handleOpenToolDiff}
                  onOpenAgent={handleOpenAgent}
                  onOpenWorkspaceFiles={onOpenWorkspaceFiles}
                  onRequestReplay={childSession.loadRun}
                  replayLoading={childSession.loading}
                />
              );
            })}
          </div>
        ) : null}

        {showEmpty ? (
          <div className="kimi-detail-panel-empty">
            {!SUBAGENT_CHILD_SESSION_ENABLED
              ? "子 Agent 详情已关闭"
              : "该子 Agent 没有可用的独立会话轨迹"}
          </div>
        ) : null}
      </div>

      {/* 输入框必须在滚动区之外：放进 .kimi-detail-panel-body 会随内容一起滚走，
          且自动滚到底时会被推到消息上方，看起来像叠在一起。 */}
      {useRealChildSession ? (
        <div className="kimi-detail-panel-composer">
          <SubAgentSessionComposer
            sessionId={childSessionId}
            sessionModel={childSession.history?.model ?? null}
            onSubmitted={handleSubmitted}
          />
        </div>
      ) : null}
    </div>
  );
});
