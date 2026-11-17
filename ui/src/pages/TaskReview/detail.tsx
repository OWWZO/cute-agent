import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, LoaderCircle, RefreshCw } from "lucide-react";
import { TaskReviewReport } from "@/components/TaskReview/TaskReviewReport";
import { getTaskReviewJob, type TaskReviewJob } from "@/services/taskReview";
import { ROUTES } from "@/router/routes";

const TERMINAL = new Set(["completed", "partial", "failed", "cancelled"]);

const STAGE_LABELS: Record<string, string> = {
  exporting: "导出轨迹",
  projecting: "重建步骤",
  deterministic_audit: "确定性体检",
  diagnostics: "失败归因",
  deep_review: "AI 深度复盘",
  narrative: "综合报告",
};

const STAGE_STATUS_LABELS: Record<string, string> = {
  completed: "完成",
  running: "进行中",
  unverified: "未验证",
  failed: "失败",
  cancelled: "已取消",
};

export default function TaskReviewDetailPage() {
  const { jobId = "" } = useParams();
  const navigate = useNavigate();
  const [job, setJob] = useState<TaskReviewJob | null>(null);
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    if (!jobId) return null;
    try {
      const next = await getTaskReviewJob(jobId);
      setJob(next);
      setError("");
      return next;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法读取复盘结果");
      return null;
    }
  }, [jobId]);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      const next = await load();
      if (cancelled || !next || TERMINAL.has(next.status)) return;
      timer = setTimeout(poll, 1000);
    };
    void poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [load]);

  const refresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  return (
    <main className="relative min-h-screen overflow-hidden bg-[var(--chat-nav)] text-[var(--chat-text)]">
      <div className="pointer-events-none fixed inset-0 bg-[radial-gradient(circle_at_50%_-10%,rgba(255,255,255,0.98),rgba(255,255,255,0)_48%)]" />
      <div className="relative mx-auto w-full max-w-5xl px-4 pb-16 pt-7 sm:px-6 sm:pt-10">
        <header className="mb-7 flex flex-wrap items-start justify-between gap-4 px-1">
          <div className="min-w-0">
            <button
              type="button"
              onClick={() => navigate(ROUTES.TASK_REVIEW)}
              className="mb-5 inline-flex h-9 items-center gap-1.5 rounded-full bg-white/65 px-3 text-[13px] font-medium text-[var(--chat-text-soft)] shadow-[0_1px_2px_rgba(0,0,0,0.025)] ring-1 ring-black/[0.045] transition hover:bg-white hover:text-[var(--chat-text)]"
            >
              <ArrowLeft className="size-4" />
              复盘记录
            </button>
            <h1 className="truncate text-[30px] font-semibold leading-tight tracking-[-0.04em]">
              {job?.title || "任务复盘"}
            </h1>
            <p className="mt-2 truncate text-[12px] text-[var(--chat-text-muted)]">
              {job?.requestIds?.length
                ? `${job.requestIds.length} 个 Run · ${job.requestIds.join(" / ")}`
                : "正在读取任务"}
            </p>
          </div>
          <button
            type="button"
            onClick={() => void refresh()}
            disabled={refreshing}
            className="inline-flex h-9 items-center gap-1.5 rounded-full bg-white/65 px-3 text-[12px] font-medium text-[var(--chat-text-soft)] shadow-[0_1px_2px_rgba(0,0,0,0.025)] ring-1 ring-black/[0.045] transition hover:bg-white hover:text-[var(--chat-text)] disabled:opacity-50"
          >
            <RefreshCw className={refreshing ? "size-3.5 animate-spin" : "size-3.5"} />
            刷新
          </button>
        </header>

        {error ? (
          <div className="mb-4 rounded-[18px] bg-red-500/10 px-4 py-3 text-[13px] leading-5 text-red-700 ring-1 ring-red-500/15">
            {error}
          </div>
        ) : null}

        {!job && !error ? (
          <section className="rounded-[28px] border border-black/[0.055] bg-white/85 shadow-[0_18px_55px_rgba(0,0,0,0.04)] backdrop-blur-xl">
            <div className="flex items-center gap-2.5 px-6 py-7 text-[13px] text-[var(--chat-text-muted)]">
              <LoaderCircle className="size-4 animate-spin" />
              正在读取复盘结果
            </div>
          </section>
        ) : null}

        {job && !TERMINAL.has(job.status) ? (
          <section className="mb-4 rounded-[24px] border border-black/[0.055] bg-white/75 px-4 py-4 shadow-[0_10px_35px_rgba(0,0,0,0.03)] backdrop-blur-xl sm:px-5">
            <div className="flex items-center gap-2 text-[13px] font-medium">
              <LoaderCircle className="size-4 animate-spin text-[var(--chat-accent)]" />
              {job.status === "deep_running"
                ? "快速体检已完成，AI 正在评分、归因并整理综合报告"
                : "正在生成快速体检"}
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {job.progress.map((item) => (
                <span
                  key={item.stage}
                  className="rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[10px] font-medium text-[var(--chat-text-muted)]"
                >
                  {STAGE_LABELS[item.stage] || item.stage} ·{" "}
                  {STAGE_STATUS_LABELS[item.status] || item.status}
                </span>
              ))}
            </div>
          </section>
        ) : null}

        {job ? <TaskReviewReport job={job} /> : null}
      </div>
    </main>
  );
}
