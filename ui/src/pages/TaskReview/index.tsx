import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  ArrowLeft,
  ChevronRight,
  ClipboardCheck,
  LoaderCircle,
  Play,
  Sparkles,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  createTaskReviewJob,
  getTaskReviewPreflight,
  listTaskReviewJobs,
  type TaskReviewPreflight,
} from "@/services/taskReview";
import { ROUTES } from "@/router/routes";

function formatMs(value?: number | null) {
  if (value == null) return "—";
  if (value < 1000) return `${Math.round(value)} ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(1)} s`;
  return `${(value / 60_000).toFixed(1)} min`;
}

function formatNumber(value?: number | null) {
  return value == null ? "—" : new Intl.NumberFormat("zh-CN").format(value);
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

export default function TaskReviewPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get("sessionId") || "";
  const rawRequestIds = searchParams.get("requestIds") || "";
  const requestIds = useMemo(
    () => rawRequestIds.split(",").map((item) => item.trim()).filter(Boolean),
    [rawRequestIds]
  );
  const isStartFlow = Boolean(sessionId && requestIds.length);
  const [preflight, setPreflight] = useState<TaskReviewPreflight | null>(null);
  const [history, setHistory] = useState<Array<Record<string, unknown>>>([]);
  const [criteria, setCriteria] = useState("");
  const [loading, setLoading] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setError("");
    if (isStartFlow) {
      setLoading(true);
      getTaskReviewPreflight(sessionId, requestIds)
        .then((data) => {
          if (!cancelled) setPreflight(data);
        })
        .catch((reason: unknown) => {
          if (!cancelled) {
            setError(reason instanceof Error ? reason.message : "无法读取本轮任务");
          }
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    } else {
      setLoading(true);
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
          if (!cancelled) setLoading(false);
        });
    }
    return () => {
      cancelled = true;
    };
  }, [isStartFlow, requestIds, sessionId]);

  const start = async () => {
    if (!preflight?.canReview || starting) return;
    setStarting(true);
    setError("");
    try {
      const job = await createTaskReviewJob({
        sessionId,
        requestIds,
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
      <div className="relative mx-auto w-full max-w-5xl px-4 pb-16 pt-7 sm:px-6 sm:pt-10">
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
              {isStartFlow ? "复盘这次任务" : "复盘记录"}
            </h1>
            <p className="mt-3 max-w-xl text-[14px] leading-6 text-[var(--chat-text-soft)]">
              {isStartFlow
                ? "先确认任务事实，再从工具选择、错误恢复和结果验证等维度检查执行路径。"
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

        {loading ? (
          <Panel>
            <div className="flex items-center gap-2.5 px-6 py-7 text-[13px] text-[var(--chat-text-muted)]">
              <LoaderCircle className="size-4 animate-spin" />
              正在读取任务信息
            </div>
          </Panel>
        ) : isStartFlow ? (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
            <Panel>
              <div className="px-5 pb-4 pt-6 sm:px-7">
                <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--chat-text-muted)]">
                  本次会话
                </div>
                <h2 className="mt-2 text-[22px] font-semibold tracking-[-0.03em]">
                  {preflight?.title || "当前会话"}
                </h2>
                {preflight ? (
                  <div className="mt-3 flex flex-wrap gap-2">
                    <span
                      className={`rounded-full px-2.5 py-1 text-[11px] font-medium ${
                        preflight.canReview
                          ? "bg-emerald-500/10 text-emerald-700"
                          : "bg-red-500/10 text-red-700"
                      }`}
                    >
                      {preflight.canReview ? "可以复盘" : "暂不可复盘"}
                    </span>
                    <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                      {preflight.requestIds.length} 个 Run
                    </span>
                    {preflight.model ? (
                      <span className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[11px] text-[var(--chat-text-soft)]">
                        {preflight.model}
                      </span>
                    ) : null}
                  </div>
                ) : null}
              </div>
              <div className="mx-3 mb-3 space-y-2 sm:mx-4 sm:mb-4">
                {preflight?.runs.map((run) => (
                  <div
                    key={run.requestId}
                    className="grid gap-2 rounded-[19px] bg-[var(--chat-surface-soft)]/75 p-3.5 sm:grid-cols-[minmax(0,1fr)_repeat(3,auto)] sm:items-center"
                  >
                    <div className="min-w-0">
                      <div className="truncate text-[13px] font-medium">{run.requestId}</div>
                      <div className="mt-0.5 text-[11px] text-[var(--chat-text-muted)]">
                        {run.agent || "agent"} · {run.status}
                      </div>
                    </div>
                    <span className="text-[11px] text-[var(--chat-text-muted)]">
                      {formatMs(run.durationMs)}
                    </span>
                    <span className="text-[11px] text-[var(--chat-text-muted)]">
                      {formatNumber(run.totalTokens)} Token
                    </span>
                    <span className="text-[11px] text-[var(--chat-text-muted)]">
                      {formatNumber(run.toolCount)} 工具
                    </span>
                  </div>
                ))}
              </div>
              {preflight?.blockers.length ? (
                <div className="mx-5 mb-5 rounded-[16px] bg-amber-500/10 px-3.5 py-3 text-[12px] leading-5 text-amber-800 sm:mx-7">
                  {preflight.blockers.join("；")}
                </div>
              ) : null}
            </Panel>

            <Panel>
              <div className="p-5 sm:p-6">
                <h2 className="text-[18px] font-semibold tracking-[-0.025em]">开始复盘</h2>
                <p className="mt-2 text-[12px] leading-5 text-[var(--chat-text-muted)]">
                  快速体检直接检查工具失败、重复调用、Token、耗时和产物。AI 复盘只提供带证据的建议。
                </p>
                <label className="mt-5 block space-y-2 text-[13px]">
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
                  disabled={!preflight?.canReview || starting}
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
              </div>
            </Panel>
          </div>
        ) : (
          <Panel>
            {history.length ? (
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
        )}
      </div>
    </main>
  );
}
