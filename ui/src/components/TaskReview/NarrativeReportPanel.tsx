import {
  BookOpenText,
  CheckCircle2,
  CircleHelp,
  Download,
  Route,
  Sparkles,
} from "lucide-react";
import { toast } from "sonner";
import MarkdownRenderer from "@/components/ActionPanel/MarkdownRenderer";
import { downloadTaskReviewReport } from "@/services/taskReview";
import type { TaskReviewNarrative } from "@/services/taskReview";
import { DeveloperActionCard } from "./DeveloperActionCard";

function reportStatusLabel(status: string) {
  if (status === "verified") return "AI 综合复盘";
  if (status === "fact_only") return "事实版报告";
  return "报告受限";
}

function withoutDeveloperActionsSection(markdown: string) {
  const lines = markdown.split(/\r?\n/);
  const start = lines.findIndex((line) => line.trim() === "## 开发者行动项");
  if (start < 0) return markdown;
  const end = lines.findIndex(
    (line, index) => index > start && line.trim().startsWith("## ")
  );
  return [...lines.slice(0, start), ...lines.slice(end < 0 ? lines.length : end)]
    .join("\n")
    .trim();
}

export function NarrativeReportPanel({
  narrative,
  jobId,
  onOpenEvidence,
}: {
  narrative: TaskReviewNarrative;
  jobId: string;
  onOpenEvidence: (ref: string) => void;
}) {
  const downloadReport = async () => {
    try {
      const blob = await downloadTaskReviewReport(jobId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${jobId}-review.md`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "下载复盘报告失败");
    }
  };
  const displayMarkdown = withoutDeveloperActionsSection(narrative.markdown);

  return (
    <section className="overflow-hidden rounded-[28px] border border-black/[0.055] bg-white/88 shadow-[0_1px_2px_rgba(0,0,0,0.03),0_18px_55px_rgba(0,0,0,0.04)] backdrop-blur-xl">
      <div className="border-b border-black/[0.045] bg-[linear-gradient(135deg,rgba(255,255,255,0.95),rgba(244,247,250,0.82))] px-5 py-5 sm:px-7 sm:py-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="max-w-3xl">
            <div className="flex items-center gap-2 text-[12px] font-medium text-[var(--chat-text-muted)]">
              <BookOpenText className="size-4" />
              Agent 综合复盘报告
            </div>
            <h2 className="mt-2 text-[22px] font-semibold leading-[1.32] tracking-[-0.03em] text-[var(--chat-text)] sm:text-[25px]">
              {narrative.headline}
            </h2>
          </div>
          <div className="flex items-center gap-2">
            <span className="rounded-full bg-[var(--chat-accent-soft)] px-2.5 py-1 text-[11px] font-medium text-[var(--chat-accent)]">
              {reportStatusLabel(narrative.status)}
            </span>
            <button
              type="button"
              onClick={() => void downloadReport()}
              className="inline-flex items-center gap-1.5 rounded-full bg-white/80 px-2.5 py-1 text-[11px] font-medium text-[var(--chat-text-soft)] ring-1 ring-black/[0.055] transition hover:bg-white hover:text-[var(--chat-accent)]"
            >
              <Download className="size-3.5" />
              下载报告
            </button>
          </div>
        </div>
        {narrative.executiveSummary.length ? (
          <div className="mt-4 grid gap-2 md:grid-cols-2">
            {narrative.executiveSummary.map((item, index) => (
              <div
                key={`${item}-${index}`}
                className="flex items-start gap-2.5 rounded-[18px] bg-white/75 px-3.5 py-3 text-[13px] leading-5 text-[var(--chat-text-soft)] ring-1 ring-black/[0.045]"
              >
                {index === 0 ? (
                  <Route className="mt-0.5 size-4 shrink-0 text-[var(--chat-accent)]" />
                ) : (
                  <Sparkles className="mt-0.5 size-4 shrink-0 text-[var(--chat-text-muted)]" />
                )}
                <span>{item}</span>
              </div>
            ))}
          </div>
        ) : null}
      </div>

      <div className="px-5 py-5 sm:px-7 sm:py-7">
        {narrative.developerActions?.length ? (
          <section className="mb-7">
            <div className="mb-3 flex flex-wrap items-end justify-between gap-2 px-1">
              <div>
                <h3 className="text-[18px] font-semibold tracking-[-0.02em] text-[var(--chat-text)]">
                  开发者行动项
                </h3>
                <p className="mt-1 text-[12px] leading-5 text-[var(--chat-text-muted)]">
                  按具体工具、失败类型和错误签名聚合，可直接复制给 Coding Agent。
                </p>
              </div>
              <span className="rounded-full bg-white/75 px-2.5 py-1 text-[10px] font-medium text-[var(--chat-text-muted)] ring-1 ring-black/[0.045]">
                {narrative.developerActions.length} 项
              </span>
            </div>
            <div className="space-y-2.5">
              {narrative.developerActions.map((action) => (
                <DeveloperActionCard
                  key={action.actionId || action.bugSignature || action.title}
                  action={action}
                  onOpenEvidence={onOpenEvidence}
                />
              ))}
            </div>
          </section>
        ) : null}
        <MarkdownRenderer
          markDownContent={displayMarkdown}
          className={[
            "max-w-none text-[14px] leading-7 text-[var(--chat-text-soft)]",
            "[&_h1]:mb-4 [&_h1]:text-[24px] [&_h1]:font-semibold [&_h1]:tracking-[-0.03em] [&_h1]:text-[var(--chat-text)]",
            "[&_h2]:mb-3 [&_h2]:mt-8 [&_h2]:border-t [&_h2]:border-black/[0.045] [&_h2]:pt-6 [&_h2]:text-[18px] [&_h2]:font-semibold [&_h2]:tracking-[-0.02em] [&_h2]:text-[var(--chat-text)]",
            "[&_h3]:mb-2 [&_h3]:mt-5 [&_h3]:text-[15px] [&_h3]:font-semibold [&_h3]:text-[var(--chat-text)]",
            "[&_p]:my-2.5 [&_ul]:my-2.5 [&_ul]:space-y-1.5 [&_ol]:my-2.5 [&_ol]:space-y-1.5 [&_li]:pl-1",
            "[&_strong]:font-semibold [&_strong]:text-[var(--chat-text)]",
            "[&_blockquote]:my-4 [&_blockquote]:border-l-2 [&_blockquote]:border-[var(--chat-accent)]/35 [&_blockquote]:pl-4 [&_blockquote]:text-[var(--chat-text-muted)]",
          ].join(" ")}
        />

        {narrative.status === "fact_only" ? (
          <div className="mt-6 flex items-start gap-2 rounded-[18px] bg-amber-500/10 px-3.5 py-3 text-[12px] leading-5 text-amber-800">
            <CircleHelp className="mt-0.5 size-4 shrink-0" />
            当前报告只使用确定性事实生成；AI 质量评分或叙事校验未完成。
          </div>
        ) : (
          <div className="mt-6 flex items-start gap-2 rounded-[18px] bg-emerald-500/10 px-3.5 py-3 text-[12px] leading-5 text-emerald-800">
            <CheckCircle2 className="mt-0.5 size-4 shrink-0" />
            报告叙事基于已验证评分与确定性归因生成，不进入官方评分。
          </div>
        )}
      </div>
    </section>
  );
}
