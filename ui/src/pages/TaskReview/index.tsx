import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  ArrowLeft,
  Check,
  ChevronRight,
  ClipboardCheck,
  Clock3,
  Coins,
  GitBranch,
  LoaderCircle,
  Play,
  Sparkles,
  Wrench,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  conversationHistoryApi,
  type ConversationRunSummary,
} from "@/services/agentConversation";
import {
  createTaskReviewJob,
  getTaskReviewPreflight,
  listTaskReviewJobs,
  type TaskReviewPreflight,
} from "@/services/taskReview";
import { ROUTES } from "@/router/routes";
import {
  canReviewTurn,
  findReviewTurn,
  groupReviewTurns,
  mergeReviewRuns,
  sameRequestIds,
  type ReviewTurn,
} from "./runSelection";

const SESSION_PAGE_SIZE = 100;
const ACTIVE_STATUSES = new Set(["RUNNING", "WAITING_INPUT"]);

function formatMs(value?: number | null) {
  if (value == null || value < 0) return "—";
  if (value < 1000) return `${Math.round(value)} ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(1)} s`;
  return `${(value / 60_000).toFixed(1)} min`;
}

function formatNumber(value?: number | null) {
  return value == null ? "—" : new Intl.NumberFormat("zh-CN").format(value);
}

function formatTime(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function statusTone(status?: string | null) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "SUCCESS") {
    return "bg-emerald-500/10 text-emerald-700";
  }
  if (ACTIVE_STATUSES.has(normalized)) {
    return "bg-blue-500/10 text-blue-700";
  }
  if (normalized === "FAILED" || normalized === "TIMEOUT") {
    return "bg-red-500/10 text-red-700";
  }
  return "bg-black/[0.045] text-[var(--chat-text-muted)]";
}

function Panel({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-[28px] border border-black/[0.055] bg-white/85 shadow-[0_1px_2px_rgba(0,0,0,0.03),0_18px_55px_rgba(0,0,0,0.04)] backdrop-blur-xl ${className}`}
    >
      {children}
    </section>
  );
}

function ReviewTurnRow({
  turn,
  index,
  selected,
  onSelect,
}: {
  turn: ReviewTurn;
  index: number;
  selected: boolean;
  onSelect: () => void;
}) {
  const reviewable = canReviewTurn(turn);
  return (
    <button
      type="button"
      disabled={!reviewable}
      onClick={onSelect}
      className={`w-full rounded-[20px] border px-3.5 py-3.5 text-left transition ${
        selected
          ? "border-[var(--chat-accent)]/20 bg-[var(--chat-accent-soft)]/70"
          : "border-transparent bg-[var(--chat-surface-soft)]/75 hover:border-black/[0.06] hover:bg-white"
      } ${reviewable ? "" : "cursor-not-allowed opacity-55"}`}
    >
      <div className="flex items-start gap-3">
        <span
          className={`mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold ${
            selected
              ? "bg-[var(--chat-accent)] text-white"
              : "bg-white text-[var(--chat-text-soft)] ring-1 ring-black/[0.045]"
          }`}
        >
          {selected ? <Check className="size-3.5" /> : index + 1}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[11px] font-semibold text-[var(--chat-text-muted)]">
              {index === 0 ? "第 1 个请求" : `第 ${index + 1} 个请求`}
            </span>
            <span
              className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${statusTone(
                turn.status
              )}`}
            >
              {turn.status || "UNKNOWN"}
            </span>
            {turn.requestIds.length > 1 ? (
              <span className="rounded-full bg-white/80 px-2 py-0.5 text-[10px] text-[var(--chat-text-muted)] ring-1 ring-black/5">
                含 {turn.requestIds.length} 个执行 Run
              </span>
            ) : null}
          </div>
          <p className="mt-1 line-clamp-2 text-[13px] font-medium leading-5 text-[var(--chat-text)]">
            {turn.queryPreview || turn.requestIds[0]}
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-[var(--chat-text-muted)]">
            <span className="inline-flex items-center gap-1">
              <Clock3 className="size-3" />
              {formatMs(turn.durationMs)}
            </span>
            <span className="inline-flex items-center gap-1">
              <Coins className="size-3" />
              {formatNumber(turn.llmCallCount)} 次模型
            </span>
            <span className="inline-flex items-center gap-1">
              <Wrench className="size-3" />
              {formatNumber(turn.toolCallCount)} 次工具
            </span>
            <span>{formatTime(turn.startedAt)}</span>
          </div>
        </div>
      </div>
    </button>
  );
}

export default function TaskReviewPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get("sessionId") || "";
  const rawRequestIds = searchParams.get("requestIds") || "";
  const initialRequestIds = useMemo(
    () =>
      rawRequestIds
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean),
    [rawRequestIds]
  );
  const isSessionFlow = Boolean(sessionId);

  const [history, setHistory] = useState<Array<Record<string, unknown>>>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [sessionRuns, setSessionRuns] = useState<ConversationRunSummary[]>([]);
  const [sessionTitle, setSessionTitle] = useState("");
  const [sessionModel, setSessionModel] = useState<string | null>(null);
  const [sessionRunCount, setSessionRunCount] = useState(0);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [sessionLoading, setSessionLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [selectedRequestIds, setSelectedRequestIds] =
    useState<string[]>(initialRequestIds);
  const [preflight, setPreflight] = useState<TaskReviewPreflight | null>(null);
  const [preflightLoading, setPreflightLoading] = useState(false);
  const [criteria, setCriteria] = useState("");
  const [starting, setStarting] = useState(false);
  const [reviewScope, setReviewScope] = useState<"run" | "tree">("run");
  const [error, setError] = useState("");
  const previousSessionIdRef = useRef("");

  const turns = useMemo(() => groupReviewTurns(sessionRuns), [sessionRuns]);
  const selectedTurn = useMemo(
    () => findReviewTurn(turns, selectedRequestIds),
    [selectedRequestIds, turns]
  );
  const selectedRequestKey = selectedRequestIds.join(",");
  const preflightRequestIds = useMemo(
    () => (selectedRequestKey ? selectedRequestKey.split(",") : []),
    [selectedRequestKey]
  );

  useEffect(() => {
    if (isSessionFlow) return;
    let cancelled = false;
    setHistoryLoading(true);
    setError("");
    listTaskReviewJobs()
      .then((data) => {
        if (!cancelled) setHistory(data.jobs || []);
      })
      .catch((reason: unknown) => {
        if (!cancelled) {
          setError(reason instanceof Error ? reason.message : "无法读取复盘记录");
        }
      })
      .finally(() => {
        if (!cancelled) setHistoryLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isSessionFlow]);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setSessionLoading(true);
    setError("");
    setSessionRuns([]);
    setNextCursor(null);
    setHasMore(false);
    conversationHistoryApi
      .getSessionDetail(sessionId, { limit: SESSION_PAGE_SIZE })
      .then((page) => {
        if (cancelled) return;
        setSessionTitle(page.title || "");
        setSessionModel(page.model || null);
        setSessionRunCount(page.runCount || 0);
        setSessionRuns(page.runs || []);
        setNextCursor(page.nextCursor || null);
        setHasMore(Boolean(page.hasMore));
      })
      .catch((reason: unknown) => {
        if (!cancelled) {
          setError(reason instanceof Error ? reason.message : "无法读取会话请求");
        }
      })
      .finally(() => {
        if (!cancelled) setSessionLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useEffect(() => {
    if (previousSessionIdRef.current === sessionId) return;
    previousSessionIdRef.current = sessionId;
    setSelectedRequestIds(initialRequestIds);
  }, [initialRequestIds, sessionId]);

  useEffect(() => {
    if (!isSessionFlow || sessionLoading || !turns.length) return;
    if (initialRequestIds.length) {
      const initialTurn = findReviewTurn(turns, initialRequestIds);
      if (
        initialTurn &&
        !sameRequestIds(selectedRequestIds, initialTurn.requestIds)
      ) {
        setSelectedRequestIds(initialTurn.requestIds);
      }
      return;
    }
    if (selectedRequestIds.length) return;
    const firstTurn = turns.find(canReviewTurn) || turns[0];
    if (firstTurn) {
      setSelectedRequestIds(firstTurn.requestIds);
    }
  }, [
    initialRequestIds,
    isSessionFlow,
    selectedRequestIds,
    sessionLoading,
    turns,
  ]);

  useEffect(() => {
    if (!sessionId || !preflightRequestIds.length) {
      setPreflight(null);
      setPreflightLoading(false);
      return;
    }
    let cancelled = false;
    setPreflight(null);
    setPreflightLoading(true);
    setError("");
    getTaskReviewPreflight(sessionId, preflightRequestIds)
      .then((data) => {
        if (!cancelled) setPreflight(data);
      })
      .catch((reason: unknown) => {
        if (!cancelled) {
          setError(reason instanceof Error ? reason.message : "无法读取所选请求");
        }
      })
      .finally(() => {
        if (!cancelled) setPreflightLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [preflightRequestIds, sessionId]);

  const selectTurn = (turn: ReviewTurn) => {
    if (!canReviewTurn(turn)) return;
    setSelectedRequestIds(turn.requestIds);
    setError("");
    const params = new URLSearchParams({ sessionId });
    params.set("requestIds", turn.requestIds.join(","));
    navigate(`${ROUTES.TASK_REVIEW}?${params.toString()}`, { replace: true });
  };

  const loadMore = async () => {
    if (!sessionId || !nextCursor || loadingMore) return;
    setLoadingMore(true);
    setError("");
    try {
      const page = await conversationHistoryApi.getSessionDetail(sessionId, {
        limit: SESSION_PAGE_SIZE,
        after: nextCursor,
      });
      setSessionRuns((current) => mergeReviewRuns(current, page.runs || []));
      setNextCursor(page.nextCursor || null);
      setHasMore(Boolean(page.hasMore));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法加载更多请求");
    } finally {
      setLoadingMore(false);
    }
  };

  const start = async () => {
    if (!preflight?.canReview || !preflightRequestIds.length || starting) return;
    setStarting(true);
    setError("");
    try {
      const job = await createTaskReviewJob({
        sessionId,
        requestIds: preflightRequestIds,
        scope: reviewScope,
        criteria: criteria.trim() || null,
      });
      navigate(`${ROUTES.TASK_REVIEW}/${encodeURIComponent(job.jobId)}`, {
        replace: true,
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "创建复盘任务失败");
      setStarting(false);
    }
  };

  return (
    <main className="relative min-h-screen overflow-hidden bg-[var(--chat-nav)] text-[var(--chat-text)]">
      <div className="pointer-events-none fixed inset-0 bg-[radial-gradient(circle_at_50%_-10%,rgba(255,255,255,0.98),rgba(255,255,255,0)_48%)]" />
      <div className="relative mx-auto w-full max-w-6xl px-4 pb-16 pt-7 sm:px-6 sm:pt-10">
        <header className="mb-7 flex flex-wrap items-start justify-between gap-4 px-1">
          <div>
            <button
              type="button"
              onClick={() => navigate(ROUTES.HOME)}
              className="mb-5 inline-flex h-9 items-center gap-1.5 rounded-full bg-white/65 px-3 text-[13px] font-medium text-[var(--chat-text-soft)] shadow-[0_1px_2px_rgba(0,0,0,0.025)] ring-1 ring-black/[0.045] transition hover:bg-white hover:text-[var(--chat-text)]"
            >
              <ArrowLeft className="size-4" />
              返回对话
            </button>
            <div className="flex items-center gap-2 text-[12px] font-medium text-[var(--chat-text-muted)]">
              <ClipboardCheck className="size-4" />
              任务复盘
            </div>
            <h1 className="mt-2 text-[32px] font-semibold leading-none tracking-[-0.045em]">
              {isSessionFlow ? "选择要复盘的请求" : "复盘记录"}
            </h1>
            <p className="mt-3 max-w-2xl text-[14px] leading-6 text-[var(--chat-text-soft)]">
              {isSessionFlow
                ? "先选择会话中的一个用户请求，再检查这一轮对应的全部执行 Run。"
                : "查看已经生成的任务体检和 AI 深度复盘。"}
            </p>
          </div>
          <div className="hidden items-center gap-2 rounded-full bg-white/60 px-3 py-1.5 text-[11px] font-medium text-[var(--chat-text-muted)] ring-1 ring-black/[0.045] sm:flex">
            <Sparkles className="size-3.5 text-[var(--chat-accent)]" />
            确定性事实 + AI 建议
          </div>
        </header>

        {error ? (
          <div className="mb-4 rounded-[18px] bg-red-500/10 px-4 py-3 text-[13px] leading-5 text-red-700 ring-1 ring-red-500/15">
            {error}
          </div>
        ) : null}

        {!isSessionFlow ? (
          <Panel>
            {historyLoading ? (
              <div className="flex items-center gap-2.5 px-6 py-7 text-[13px] text-[var(--chat-text-muted)]">
                <LoaderCircle className="size-4 animate-spin" />
                正在读取复盘记录
              </div>
            ) : history.length ? (
              <div className="px-2.5 py-2.5">
                {history.map((item, index) => {
                  const jobId = String(item.jobId || "");
                  const completion = item.completion as
                    | { conclusion?: string; summary?: string }
                    | undefined;
                  return (
                    <button
                      key={jobId}
                      type="button"
                      className={`group flex w-full items-center gap-3 rounded-[20px] px-3.5 py-3.5 text-left transition hover:bg-[var(--chat-surface-soft)] ${
                        index ? "mt-0.5" : ""
                      }`}
                      onClick={() =>
                        navigate(`${ROUTES.TASK_REVIEW}/${encodeURIComponent(jobId)}`)
                      }
                    >
                      <span className="flex size-10 shrink-0 items-center justify-center rounded-[14px] bg-[var(--chat-surface-soft)] text-[var(--chat-text-soft)] group-hover:bg-white">
                        <ClipboardCheck className="size-[18px]" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="truncate text-[14px] font-medium">
                            {String(item.title || jobId)}
                          </span>
                          <span className="rounded-full bg-black/[0.04] px-2 py-0.5 text-[10px] text-[var(--chat-text-muted)]">
                            {String(item.status || "unknown")}
                          </span>
                        </div>
                        <div className="mt-1 line-clamp-1 text-[12px] text-[var(--chat-text-muted)]">
                          {completion?.summary || "查看本次任务复盘"}
                        </div>
                      </div>
                      <ChevronRight className="size-4 shrink-0 text-[var(--chat-text-muted)] transition-transform group-hover:translate-x-0.5" />
                    </button>
                  );
                })}
              </div>
            ) : (
              <div className="px-6 py-16 text-center">
                <div className="mx-auto flex size-12 items-center justify-center rounded-[18px] bg-[var(--chat-surface-soft)] text-[var(--chat-text-muted)]">
                  <ClipboardCheck className="size-5" />
                </div>
                <div className="mt-4 text-[15px] font-medium">还没有复盘记录</div>
                <p className="mx-auto mt-1.5 max-w-sm text-[12px] leading-5 text-[var(--chat-text-muted)]">
                  完成一轮任务后，从消息工具栏点击“复盘这次任务”。
                </p>
              </div>
            )}
          </Panel>
        ) : (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
            <Panel>
              <div className="px-5 pb-4 pt-6 sm:px-7">
                <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--chat-text-muted)]">
                  会话请求
                </div>
                <h2 className="mt-2 text-[22px] font-semibold tracking-[-0.03em]">
                  {sessionTitle || preflight?.title || "当前会话"}
                </h2>
                <div className="mt-3 flex flex-wrap gap-2">
                  <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                    {formatNumber(sessionRunCount || sessionRuns.length)} 个执行 Run
                  </span>
                  <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                    {hasMore
                      ? `已加载 ${formatNumber(turns.length)} 个请求`
                      : `${formatNumber(turns.length)} 个用户请求`}
                  </span>
                  {sessionModel || preflight?.model ? (
                    <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                      {sessionModel || preflight?.model}
                    </span>
                  ) : null}
                </div>
              </div>
              <div className="mx-3 mb-3 space-y-2 sm:mx-4 sm:mb-4">
                {sessionLoading ? (
                  <div className="flex items-center gap-2.5 rounded-[20px] bg-[var(--chat-surface-soft)]/70 px-4 py-6 text-[13px] text-[var(--chat-text-muted)]">
                    <LoaderCircle className="size-4 animate-spin" />
                    正在读取会话中的请求
                  </div>
                ) : turns.length ? (
                  turns.map((turn, index) => (
                    <ReviewTurnRow
                      key={turn.key}
                      turn={turn}
                      index={index}
                      selected={
                        selectedTurn?.key === turn.key ||
                        turn.requestIds.every((id) =>
                          selectedRequestIds.includes(id)
                        )
                      }
                      onSelect={() => selectTurn(turn)}
                    />
                  ))
                ) : (
                  <div className="rounded-[20px] bg-[var(--chat-surface-soft)]/70 px-4 py-6 text-[13px] text-[var(--chat-text-muted)]">
                    当前会话还没有可复盘的执行请求。
                  </div>
                )}
                {hasMore ? (
                  <Button
                    type="button"
                    variant="outline"
                    className="h-10 w-full rounded-full border-black/[0.06] bg-white/70 text-[12px] shadow-none"
                    onClick={() => void loadMore()}
                    disabled={loadingMore}
                  >
                    {loadingMore ? (
                      <LoaderCircle className="size-4 animate-spin" />
                    ) : (
                      <ChevronRight className="size-4 rotate-90" />
                    )}
                    {loadingMore ? "正在加载" : "加载更多请求"}
                  </Button>
                ) : null}
              </div>
            </Panel>

            <Panel>
              <div className="p-5 sm:p-6">
                {!selectedRequestIds.length ? (
                  <div className="py-8 text-center">
                    <div className="mx-auto flex size-12 items-center justify-center rounded-[18px] bg-[var(--chat-surface-soft)] text-[var(--chat-text-muted)]">
                      <ClipboardCheck className="size-5" />
                    </div>
                    <div className="mt-4 text-[15px] font-medium">
                      先选择一个用户请求
                    </div>
                    <p className="mt-1.5 text-[12px] leading-5 text-[var(--chat-text-muted)]">
                      支持复盘当前会话中的任意一轮，不只是最近一轮。
                    </p>
                  </div>
                ) : preflightLoading ? (
                  <div className="flex items-center gap-2.5 py-8 text-[13px] text-[var(--chat-text-muted)]">
                    <LoaderCircle className="size-4 animate-spin" />
                    正在读取所选请求
                  </div>
                ) : preflight ? (
                  <>
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="text-[18px] font-semibold tracking-[-0.025em]">
                        复盘这一轮
                      </h2>
                      <span
                        className={`rounded-full px-2.5 py-1 text-[11px] font-medium ${
                          preflight.canReview
                            ? "bg-emerald-500/10 text-emerald-700"
                            : "bg-red-500/10 text-red-700"
                        }`}
                      >
                        {preflight.canReview ? "可以复盘" : "暂不可复盘"}
                      </span>
                    </div>
                    <p className="mt-2 line-clamp-3 text-[13px] leading-5 text-[var(--chat-text-soft)]">
                      {selectedTurn?.queryPreview ||
                        `已选择 ${preflight.requestIds.length} 个执行 Run`}
                    </p>
                    <div className="mt-3 flex flex-wrap gap-2">
                      <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                        {preflight.requestIds.length} 个 Run
                      </span>
                      {preflight.model ? (
                        <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                          {preflight.model}
                        </span>
                      ) : null}
                    </div>
                    <div className="mt-4 space-y-2">
                      {preflight.runs.map((run) => (
                        <div
                          key={run.requestId}
                          className="rounded-[16px] bg-[var(--chat-surface-soft)]/75 px-3 py-2.5"
                        >
                          <div className="flex items-center justify-between gap-2">
                            <span className="truncate text-[11px] font-medium text-[var(--chat-text)]">
                              {run.requestId}
                            </span>
                            <span
                              className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] ${statusTone(
                                run.status
                              )}`}
                            >
                              {run.status}
                            </span>
                          </div>
                          <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-[var(--chat-text-muted)]">
                            <span>{formatMs(run.durationMs)}</span>
                            <span>{formatNumber(run.totalTokens)} Token</span>
                            <span>{formatNumber(run.toolCount)} 工具</span>
                          </div>
                        </div>
                      ))}
                    </div>
                    {preflight.blockers.length ? (
                      <div className="mt-3 rounded-[16px] bg-amber-500/10 px-3.5 py-3 text-[12px] leading-5 text-amber-800">
                        {preflight.blockers.join("；")}
                      </div>
                    ) : null}
                    <div className="mt-5 space-y-2 text-[13px]">
                      <span className="font-medium">复盘范围</span>
                      <div className="grid grid-cols-2 gap-2">
                        <button
                          type="button"
                          onClick={() => setReviewScope("run")}
                          className={`rounded-[14px] border px-3 py-2.5 text-left text-[12px] transition ${
                            reviewScope === "run"
                              ? "border-[var(--chat-accent)]/30 bg-[var(--chat-accent-soft)] text-[var(--chat-text)]"
                              : "border-black/[0.055] bg-white/75 text-[var(--chat-text-soft)]"
                          }`}
                        >
                          复盘这一轮
                          <span className="mt-0.5 block text-[10px] opacity-70">
                            仅当前会话的请求
                          </span>
                        </button>
                        <button
                          type="button"
                          onClick={() => setReviewScope("tree")}
                          className={`inline-flex items-start gap-1.5 rounded-[14px] border px-3 py-2.5 text-left text-[12px] transition ${
                            reviewScope === "tree"
                              ? "border-[var(--chat-accent)]/30 bg-[var(--chat-accent-soft)] text-[var(--chat-text)]"
                              : "border-black/[0.055] bg-white/75 text-[var(--chat-text-soft)]"
                          }`}
                        >
                          <GitBranch className="mt-0.5 size-3.5 shrink-0" />
                          <span>
                            复盘整棵 Agent 树
                            <span className="mt-0.5 block text-[10px] opacity-70">
                              主会话 + 全部子会话
                            </span>
                          </span>
                        </button>
                      </div>
                    </div>
                    <label className="mt-4 block space-y-2 text-[13px]">
                      <span className="font-medium">验收标准（可选）</span>
                      <Textarea
                        value={criteria}
                        onChange={(event) => setCriteria(event.target.value)}
                        placeholder="例如：生成 report.md；报告中包含 3 条结论；不要修改原始文件。"
                        className="min-h-28 rounded-[18px] border-black/[0.06] bg-[var(--chat-surface-soft)]/70 text-[13px] leading-5 shadow-none focus-visible:ring-[var(--chat-accent)]/20"
                      />
                    </label>
                    <Button
                      className="mt-4 h-11 w-full rounded-full bg-[var(--chat-accent)] text-[13px] font-semibold text-white shadow-none hover:bg-[var(--chat-accent-hover)]"
                      onClick={() => void start()}
                      disabled={!preflight.canReview || starting}
                    >
                      {starting ? (
                        <LoaderCircle className="size-4 animate-spin" />
                      ) : (
                        <Play className="size-4 fill-current" />
                      )}
                      {starting ? "正在开始复盘" : "开始复盘"}
                    </Button>
                    <p className="mt-3 text-[10px] leading-4 text-[var(--chat-text-muted)]">
                      Judge 直接复用当前会话所用模型，密钥不会进入前端或 Trace。
                    </p>
                  </>
                ) : (
                  <div className="py-8 text-center text-[13px] text-[var(--chat-text-muted)]">
                    无法读取所选请求的复盘信息。
                  </div>
                )}
              </div>
            </Panel>
          </div>
        )}
      </div>
    </main>
  );
}
