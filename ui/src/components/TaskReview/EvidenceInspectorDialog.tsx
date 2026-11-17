import { useEffect, useState } from "react";
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Copy,
  Download,
  FileJson,
  LoaderCircle,
  RotateCcw,
  Wrench,
  X,
} from "lucide-react";
import { toast } from "sonner";
import {
  getTaskReviewEvidence,
  TaskReviewApiError,
  type TaskReviewEvidenceDetail,
  type TaskReviewEvidenceIndexEntry,
} from "@/services/taskReview";
import { cn } from "@/lib/utils";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";

export type EvidencePreview = {
  toolName?: string;
  step?: number | null;
  status?: string;
  argumentsPreview?: string;
  resultPreview?: string;
  errorMsg?: string;
};

function formatDuration(value?: number | null) {
  if (value == null) return "—";
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(2)} s`;
}

function prettyValue(raw: string, parsed: unknown) {
  if (raw) return raw;
  if (parsed == null) return "";
  return JSON.stringify(parsed, null, 2);
}

function EvidencePanel({
  title,
  raw,
  parsed,
  emptyText,
  truncated,
  parseError,
}: {
  title: string;
  raw: string;
  parsed: unknown;
  emptyText: string;
  truncated?: boolean;
  parseError?: string | null;
}) {
  const text = prettyValue(raw, parsed);
  const copy = async () => {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${title}已复制`);
    } catch {
      toast.error("复制失败，请手动选择内容");
    }
  };
  const download = () => {
    if (!text) return;
    const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${title}.txt`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  return (
    <section className="min-w-0 overflow-hidden rounded-[20px] border border-black/[0.055] bg-white/80">
      <div className="flex items-center justify-between gap-3 border-b border-black/[0.045] px-4 py-3">
        <div className="flex items-center gap-2 text-[12px] font-semibold text-[var(--chat-text)]">
          <FileJson className="size-3.5 text-[var(--chat-text-muted)]" />
          {title}
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            disabled={!text}
            onClick={download}
            className="flex size-6 items-center justify-center rounded-full bg-[var(--chat-surface-soft)] text-[var(--chat-text-muted)] transition hover:text-[var(--chat-accent)] disabled:cursor-not-allowed disabled:opacity-40"
            title={`下载${title}`}
          >
            <Download className="size-3" />
          </button>
          <button
            type="button"
            disabled={!text}
            onClick={() => void copy()}
            className="inline-flex items-center gap-1 rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-[10px] font-medium text-[var(--chat-text-soft)] transition hover:text-[var(--chat-accent)] disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Copy className="size-3" />
            复制
          </button>
        </div>
      </div>
      <div className="p-3">
        {text ? (
          <pre className="max-h-[46vh] overflow-auto whitespace-pre-wrap break-words rounded-[14px] bg-[#171717] p-3.5 font-mono text-[11px] leading-5 text-white/90">
            {text}
          </pre>
        ) : (
          <div className="flex min-h-28 items-center justify-center rounded-[14px] bg-[var(--chat-surface-soft)] px-4 text-center text-[12px] text-[var(--chat-text-muted)]">
            {emptyText}
          </div>
        )}
        {parseError ? (
          <p className="mt-2 text-[10px] leading-4 text-amber-700">
            JSON 解析提示：{parseError}
          </p>
        ) : null}
        {truncated ? (
          <p className="mt-2 flex items-center gap-1.5 text-[10px] text-amber-700">
            <AlertTriangle className="size-3" />
            内容过长，服务端已保留头尾并截断中间部分。
          </p>
        ) : null}
      </div>
    </section>
  );
}

export function EvidenceInspectorDialog({
  open,
  onOpenChange,
  jobId,
  evidenceRef,
  indexEntry,
  preview,
  onNavigateRef,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  jobId: string;
  evidenceRef: string | null;
  indexEntry?: TaskReviewEvidenceIndexEntry;
  preview?: EvidencePreview;
  onNavigateRef: (ref: string) => void;
}) {
  const [detail, setDetail] = useState<TaskReviewEvidenceDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<TaskReviewApiError | Error | null>(null);

  useEffect(() => {
    if (!open || !evidenceRef) {
      setDetail(null);
      setError(null);
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError(null);
    void getTaskReviewEvidence(jobId, evidenceRef)
      .then((value) => {
        if (active) setDetail(value);
      })
      .catch((reason: unknown) => {
        if (active) {
          setDetail(null);
          setError(
            reason instanceof Error ? reason : new Error("读取工具证据失败")
          );
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [evidenceRef, jobId, open]);

  const headerTool = detail?.toolName || indexEntry?.toolName || preview?.toolName;
  const headerStatus = detail?.status || indexEntry?.status || preview?.status;
  const headerStep = detail?.step ?? indexEntry?.step ?? preview?.step;
  const downloaded = detail
    ? JSON.stringify(detail, null, 2)
    : JSON.stringify({ ref: evidenceRef, preview }, null, 2);
  const downloadJson = () => {
    const blob = new Blob([downloaded], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "tool-evidence.json";
    anchor.click();
    URL.revokeObjectURL(url);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="left-auto right-3 top-3 grid h-[calc(100dvh-1.5rem)] w-[calc(100vw-1.5rem)] max-w-[760px] translate-x-0 translate-y-0 gap-0 overflow-hidden rounded-[28px] border border-black/[0.065] bg-white/95 p-0 shadow-[0_32px_100px_rgba(0,0,0,0.22)] backdrop-blur-2xl sm:right-4 sm:top-4 sm:h-[calc(100dvh-2rem)] sm:max-w-[760px]"
      >
        <div className="flex items-start justify-between gap-4 border-b border-black/[0.055] bg-[linear-gradient(135deg,rgba(255,255,255,0.98),rgba(244,247,250,0.9))] px-5 py-4 sm:px-6">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="flex size-8 items-center justify-center rounded-full bg-[var(--chat-accent-soft)] text-[var(--chat-accent)]">
                <Wrench className="size-4" />
              </span>
              <DialogTitle className="text-[18px] font-semibold tracking-[-0.02em] text-[var(--chat-text)]">
                工具证据
              </DialogTitle>
              {headerTool ? (
                <span className="rounded-full bg-white/80 px-2.5 py-1 text-[10px] font-medium text-[var(--chat-text-soft)] ring-1 ring-black/5">
                  {headerTool}
                </span>
              ) : null}
              {headerStatus ? (
                <span
                  className={cn(
                    "rounded-full px-2.5 py-1 text-[10px] font-medium",
                    headerStatus === "SUCCESS"
                      ? "bg-emerald-500/10 text-emerald-700"
                      : "bg-red-500/10 text-red-700"
                  )}
                >
                  {headerStatus}
                </span>
              ) : null}
            </div>
            <DialogDescription className="mt-2 truncate font-mono text-[10px] text-[var(--chat-text-muted)]">
              {evidenceRef || "未选择证据"}
            </DialogDescription>
            {headerStep != null ? (
              <p className="mt-1 text-[11px] text-[var(--chat-text-muted)]">
                步骤 {headerStep}
                {detail?.timing?.durationMs != null
                  ? ` · ${formatDuration(detail.timing.durationMs)}`
                  : ""}
              </p>
            ) : null}
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            <button
              type="button"
              onClick={downloadJson}
              className="flex size-8 items-center justify-center rounded-full bg-white/80 text-[var(--chat-text-muted)] ring-1 ring-black/[0.055] transition hover:text-[var(--chat-accent)]"
              title="下载 JSON"
            >
              <Download className="size-3.5" />
            </button>
            <button
              type="button"
              onClick={() => onOpenChange(false)}
              className="flex size-8 items-center justify-center rounded-full bg-white/80 text-[var(--chat-text-muted)] ring-1 ring-black/[0.055] transition hover:text-[var(--chat-text)]"
              title="关闭"
            >
              <X className="size-3.5" />
            </button>
          </div>
        </div>

        <div className="min-h-0 overflow-y-auto px-4 py-4 sm:px-6">
          {loading ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2 rounded-[18px] bg-[var(--chat-surface-soft)] px-3.5 py-3 text-[12px] text-[var(--chat-text-muted)]">
                <LoaderCircle className="size-4 animate-spin" />
                正在从已保存的 Trace 读取完整入参和出参…
              </div>
              <div className="grid gap-3 lg:grid-cols-2">
                <div className="h-64 animate-pulse rounded-[20px] bg-black/[0.035]" />
                <div className="h-64 animate-pulse rounded-[20px] bg-black/[0.035]" />
              </div>
            </div>
          ) : null}

          {!loading && error ? (
            <div className="space-y-3">
              <div className="flex items-start gap-2.5 rounded-[18px] bg-amber-500/10 px-3.5 py-3 text-[12px] leading-5 text-amber-800">
                <AlertTriangle className="mt-0.5 size-4 shrink-0" />
                <div>
                  <div className="font-medium">完整证据读取失败</div>
                  <div className="mt-1">{error.message}</div>
                  {error instanceof TaskReviewApiError &&
                  error.candidates?.length ? (
                    <div className="mt-2 space-y-1.5">
                      <div className="text-[11px]">
                        命中多个 Run，请选择一个精确证据：
                      </div>
                      {error.candidates.map((candidate, index) => {
                        const requestId = String(candidate.requestId || "");
                        const toolCallId = String(candidate.toolCallId || "");
                        if (!requestId || !toolCallId) return null;
                        const preciseRef = `tool:${requestId}:${toolCallId}`;
                        return (
                          <button
                            key={`${preciseRef}-${index}`}
                            type="button"
                            onClick={() => onNavigateRef(preciseRef)}
                            className="block max-w-full truncate rounded-full bg-white/80 px-2.5 py-1 font-mono text-[9px] text-amber-800 ring-1 ring-amber-500/15 transition hover:text-[var(--chat-accent)]"
                          >
                            {preciseRef} · {String(candidate.toolName || "unknown")}
                          </button>
                        );
                      })}
                    </div>
                  ) : null}
                </div>
              </div>
              <div className="rounded-full bg-[var(--chat-surface-soft)] px-3 py-1.5 text-center text-[10px] text-[var(--chat-text-muted)]">
                当前展示时间线预览，不是完整证据
              </div>
              <div className="grid gap-3 lg:grid-cols-2">
                <EvidencePanel
                  title="入参预览"
                  raw={preview?.argumentsPreview || ""}
                  parsed={null}
                  emptyText="时间线也没有保存入参预览。"
                />
                <EvidencePanel
                  title="出参预览"
                  raw={preview?.resultPreview || preview?.errorMsg || ""}
                  parsed={null}
                  emptyText="时间线也没有保存出参预览。"
                />
              </div>
            </div>
          ) : null}

          {!loading && !error && detail ? (
            <div className="space-y-3">
              <div className="grid gap-2 sm:grid-cols-3">
                <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3 py-2.5">
                  <div className="flex items-center gap-1.5 text-[10px] text-[var(--chat-text-muted)]">
                    <Wrench className="size-3" />
                    调用状态
                  </div>
                  <div className="mt-1 flex items-center gap-1.5 text-[13px] font-semibold text-[var(--chat-text)]">
                    {detail.failed ? (
                      <AlertTriangle className="size-3.5 text-red-600" />
                    ) : (
                      <CheckCircle2 className="size-3.5 text-emerald-600" />
                    )}
                    {detail.status}
                  </div>
                </div>
                <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3 py-2.5">
                  <div className="flex items-center gap-1.5 text-[10px] text-[var(--chat-text-muted)]">
                    <Clock3 className="size-3" />
                    调用耗时
                  </div>
                  <div className="mt-1 text-[13px] font-semibold text-[var(--chat-text)]">
                    {formatDuration(detail.timing.durationMs)}
                  </div>
                </div>
                <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3 py-2.5">
                  <div className="flex items-center gap-1.5 text-[10px] text-[var(--chat-text-muted)]">
                    <RotateCcw className="size-3" />
                    是否恢复
                  </div>
                  <div className="mt-1 text-[13px] font-semibold text-[var(--chat-text)]">
                    {detail.failed
                      ? detail.recovery.recovered
                        ? "已恢复"
                        : "未恢复"
                      : "无需恢复"}
                  </div>
                </div>
              </div>

              {detail.error.message ? (
                <div className="rounded-[16px] bg-red-500/[0.055] px-3.5 py-3 text-[12px] leading-5 text-red-700 ring-1 ring-red-500/10">
                  <span className="font-medium">{detail.error.class}</span>
                  <span className="mx-1.5 text-red-300">·</span>
                  {detail.error.message}
                </div>
              ) : null}

              {detail.recovery.refs.length ? (
                <div className="flex flex-wrap items-center gap-2 rounded-[16px] bg-white/75 px-3 py-2.5 ring-1 ring-black/[0.045]">
                  <RotateCcw className="size-3.5 text-[var(--chat-text-muted)]" />
                  <span className="text-[11px] text-[var(--chat-text-muted)]">
                    恢复调用
                  </span>
                  {detail.recovery.refs.map((ref) => (
                    <button
                      key={ref}
                      type="button"
                      onClick={() => onNavigateRef(ref)}
                      className="inline-flex min-w-0 items-center gap-1 truncate rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 font-mono text-[9px] text-[var(--chat-text-soft)] transition hover:text-[var(--chat-accent)]"
                    >
                      {ref}
                      <ArrowRight className="size-2.5 shrink-0" />
                    </button>
                  ))}
                </div>
              ) : null}

              <div className="grid gap-3 lg:grid-cols-2">
                <EvidencePanel
                  title="入参"
                  raw={detail.input.raw}
                  parsed={detail.input.parsed}
                  emptyText="该工具调用没有保存入参。"
                  truncated={detail.input.truncated}
                  parseError={detail.input.parseError}
                />
                <EvidencePanel
                  title="出参"
                  raw={detail.output.raw}
                  parsed={detail.output.parsed}
                  emptyText={
                    detail.output.source === "missing"
                      ? "Trace 没有记录工具结果或观察文本。"
                      : "该工具调用没有出参。"
                  }
                  truncated={detail.output.truncated}
                />
              </div>

              {detail.artifacts.length ? (
                <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3.5 py-3">
                  <div className="text-[11px] font-semibold text-[var(--chat-text)]">
                    关联产物
                  </div>
                  <div className="mt-2 space-y-1.5">
                    {detail.artifacts.map((artifact) => (
                      <div
                        key={artifact.artifactId}
                        className="flex flex-wrap items-center gap-2 text-[10px] text-[var(--chat-text-muted)]"
                      >
                        <code className="rounded bg-white/80 px-1.5 py-0.5">
                          {artifact.relativeRef || artifact.artifactId}
                        </code>
                        <span>{artifact.mimeType || "未知类型"}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}

              {detail.limitations.length ? (
                <div className="rounded-[16px] border border-amber-500/10 bg-amber-500/[0.075] px-3.5 py-3 text-[11px] leading-5 text-amber-800">
                  {detail.limitations.map((item) => (
                    <p key={item}>· {item}</p>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
