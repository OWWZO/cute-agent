import type { ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  CircleHelp,
  Clock3,
  Coins,
  ListChecks,
  ShieldAlert,
  Sparkles,
  Wrench,
  XCircle,
} from "lucide-react";
import type {
  TaskReviewFinding,
  TaskReviewJob,
  TaskReviewQuality,
} from "@/services/taskReview";
import { cn } from "@/lib/utils";

const CONCLUSION_LABELS: Record<string, string> = {
  completed: "完成",
  partial: "部分完成",
  incomplete: "未完成",
  unknown: "无法判断",
};

const LEVEL_LABELS: Record<string, string> = {
  high: "高",
  medium: "中",
  low: "低",
  unknown: "无法判断",
};

const SEVERITY_LABELS: Record<string, string> = {
  blocker: "阻断",
  warning: "注意",
  info: "信息",
};

const STEP_STATUS_LABELS: Record<string, string> = {
  SUCCESS: "成功",
  FAILED: "失败",
  TIMEOUT: "超时",
  STOPPED: "已停止",
  REQUESTED: "已发起",
  RUNNING: "进行中",
  MODEL_STEP: "模型步骤",
  COMPLETED: "已完成",
};

function formatMs(value?: number | null) {
  if (value == null || value < 0) return "—";
  if (value < 1000) return `${Math.round(value)} ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(1)} s`;
  return `${(value / 60_000).toFixed(1)} min`;
}

function formatNumber(value?: number | null) {
  if (value == null) return "—";
  return new Intl.NumberFormat("zh-CN").format(value);
}

function conclusionTone(conclusion?: string) {
  if (conclusion === "completed") {
    return "bg-emerald-500/10 text-emerald-700 ring-emerald-500/20";
  }
  if (conclusion === "partial") {
    return "bg-amber-500/10 text-amber-700 ring-amber-500/20";
  }
  if (conclusion === "incomplete") {
    return "bg-red-500/10 text-red-700 ring-red-500/20";
  }
  return "bg-black/[0.045] text-[var(--chat-text-soft)] ring-black/5";
}

function stepStatusTone(status?: string) {
  if (status === "SUCCESS" || status === "COMPLETED") {
    return "bg-emerald-500/10 text-emerald-700";
  }
  if (status === "FAILED" || status === "TIMEOUT") {
    return "bg-red-500/10 text-red-700";
  }
  if (status === "REQUESTED" || status === "RUNNING") {
    return "bg-blue-500/10 text-blue-700";
  }
  return "bg-black/[0.045] text-[var(--chat-text-muted)]";
}

function Panel({
  title,
  description,
  icon,
  action,
  children,
  className,
}: {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={cn(
        "overflow-hidden rounded-[28px] border border-black/[0.055] bg-white/85 shadow-[0_1px_2px_rgba(0,0,0,0.03),0_16px_50px_rgba(0,0,0,0.035)] backdrop-blur-xl",
        className
      )}
    >
      <div className="flex items-start justify-between gap-4 px-5 pb-3 pt-5 sm:px-7 sm:pt-7">
        <div>
          <h2 className="flex items-center gap-2 text-[19px] font-semibold tracking-[-0.025em] text-[var(--chat-text)]">
            {icon}
            {title}
          </h2>
          {description ? (
            <p className="mt-1.5 text-[13px] leading-5 text-[var(--chat-text-muted)]">
              {description}
            </p>
          ) : null}
        </div>
        {action}
      </div>
      <div className="px-5 pb-5 pt-2 sm:px-7 sm:pb-7">{children}</div>
    </section>
  );
}

function Metric({
  icon,
  label,
  value,
}: {
  icon: ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="min-w-0 rounded-[18px] bg-[var(--chat-surface-soft)] px-3.5 py-3">
      <div className="flex items-center gap-1.5 text-[11px] font-medium text-[var(--chat-text-muted)]">
        {icon}
        {label}
      </div>
      <div className="mt-1.5 truncate text-[17px] font-semibold tracking-[-0.02em] text-[var(--chat-text)]">
        {value}
      </div>
    </div>
  );
}

function FindingCard({ finding }: { finding: TaskReviewFinding }) {
  const evidence = finding.evidence || [];
  return (
    <div className="rounded-[18px] bg-[var(--chat-surface-soft)]/80 p-3.5">
      <div className="flex items-start gap-2.5">
        <AlertTriangle className="mt-0.5 size-4 shrink-0 text-amber-600" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[14px] font-medium text-[var(--chat-text)]">
              {finding.title}
            </span>
            <span className="rounded-full bg-white/80 px-2 py-0.5 text-[10px] font-medium text-[var(--chat-text-muted)] ring-1 ring-black/5">
              {SEVERITY_LABELS[finding.severity] || finding.severity}
            </span>
            {finding.runRequestId ? (
              <span className="text-[11px] text-[var(--chat-text-muted)]">
                {finding.runRequestId}
              </span>
            ) : null}
          </div>
          <p className="mt-1.5 text-[13px] leading-5 text-[var(--chat-text-soft)]">
            {finding.detail}
          </p>
          {finding.evidenceRefs?.length ? (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {finding.evidenceRefs.map((ref) => (
                <code
                  key={ref}
                  className="rounded-md bg-white/80 px-1.5 py-1 text-[10px] text-[var(--chat-text-muted)] ring-1 ring-black/5"
                >
                  {ref}
                </code>
              ))}
            </div>
          ) : null}
          {evidence.length ? (
            <details className="mt-2 text-[12px] text-[var(--chat-text-muted)]">
              <summary className="cursor-pointer select-none">查看证据</summary>
              <div className="mt-2 space-y-2">
                {evidence.map((item, index) => (
                  <div
                    key={`${item.kind || "evidence"}-${index}`}
                    className="rounded-xl bg-white/75 p-2.5 ring-1 ring-black/5"
                  >
                    <div className="font-medium text-[var(--chat-text)]">
                      {item.kind || "证据"} {item.id || ""}
                    </div>
                    {item.quote ? (
                      <p className="mt-1 whitespace-pre-wrap break-words">
                        {item.quote}
                      </p>
                    ) : null}
                  </div>
                ))}
              </div>
            </details>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function QualitySection({ quality }: { quality: TaskReviewQuality }) {
  const statusLabel =
    quality.status === "verified"
      ? "已分析"
      : quality.status === "running"
        ? "分析中"
        : quality.status === "failed"
          ? "分析失败"
          : "未验证";

  return (
    <Panel
      title="AI 深度复盘"
      description="只评价执行路径与结果质量，不替代确定性事实和官方评分。"
      icon={<Sparkles className="size-[18px] text-[var(--chat-accent)]" />}
      action={
        <span className="rounded-full bg-[var(--chat-accent-soft)] px-2.5 py-1 text-[11px] font-medium text-[var(--chat-accent)]">
          {statusLabel}
        </span>
      }
    >
      <div className="space-y-4">
        <p className="text-[14px] leading-6 text-[var(--chat-text-soft)]">
          {quality.summary || "AI 复盘建议，不是官方评分。"}
        </p>
        {quality.dimensions?.length ? (
          <div className="grid gap-3 md:grid-cols-2">
            {quality.dimensions.map((dimension) => (
              <div
                key={dimension.id}
                className="rounded-[20px] bg-[var(--chat-surface-soft)]/75 p-4"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[14px] font-medium text-[var(--chat-text)]">
                    {dimension.label}
                  </span>
                  <span
                    className={cn(
                      "rounded-full px-2 py-0.5 text-[11px] font-medium",
                      dimension.level === "low"
                        ? "bg-red-500/10 text-red-700"
                        : dimension.level === "high"
                          ? "bg-emerald-500/10 text-emerald-700"
                          : "bg-white/80 text-[var(--chat-text-soft)] ring-1 ring-black/5"
                    )}
                  >
                    {LEVEL_LABELS[dimension.level] || dimension.level}
                  </span>
                </div>
                <p className="mt-2 text-[13px] leading-5 text-[var(--chat-text-soft)]">
                  {dimension.reason}
                </p>
                {dimension.evidence?.length ? (
                  <details className="mt-2 text-[11px] text-[var(--chat-text-muted)]">
                    <summary className="cursor-pointer select-none">查看证据</summary>
                    <div className="mt-2 space-y-2">
                      {dimension.evidence.map((item, index) => (
                        <p
                          key={`${item.kind || "dimension"}-${index}`}
                          className="whitespace-pre-wrap break-words rounded-xl bg-white/75 p-2.5 ring-1 ring-black/5"
                        >
                          {item.quote || item.id || item.kind}
                        </p>
                      ))}
                    </div>
                  </details>
                ) : null}
              </div>
            ))}
          </div>
        ) : null}
        {quality.findings?.length ? (
          <div className="space-y-2">
            <h3 className="text-[13px] font-semibold text-[var(--chat-text)]">
              语义问题
            </h3>
            {quality.findings.map((finding) => (
              <FindingCard key={`${finding.code}-${finding.title}`} finding={finding} />
            ))}
          </div>
        ) : null}
        {quality.suggestions?.length ? (
          <div className="rounded-[20px] bg-[var(--chat-surface-soft)]/75 p-4">
            <div className="flex items-center gap-2 text-[13px] font-semibold text-[var(--chat-text)]">
              <ListChecks className="size-4" />
              改进建议
            </div>
            <ul className="mt-2.5 space-y-2 text-[13px] leading-5 text-[var(--chat-text-soft)]">
              {quality.suggestions.map((item, index) => (
                <li key={`${item.dimension || "suggestion"}-${index}`}>
                  <span className="font-medium text-[var(--chat-text)]">
                    {item.title}：
                  </span>
                  {item.detail}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        {quality.error?.message ? (
          <div className="flex items-start gap-2 rounded-[18px] bg-amber-500/10 p-3.5 text-[13px] leading-5 text-amber-800">
            <CircleHelp className="mt-0.5 size-4 shrink-0" />
            <span>{quality.error.message}</span>
          </div>
        ) : null}
        <p className="text-[11px] text-[var(--chat-text-muted)]">
          Judge：{quality.judgeModel || "当前会话模型 · 待深度复盘确认"} ·{" "}
          {quality.advisory === false ? "非 advisory" : "AI 复盘建议，不是官方评分"}
        </p>
      </div>
    </Panel>
  );
}

type TaskReviewStep = NonNullable<
  NonNullable<TaskReviewJob["fast"]["runs"][number]["timeline"]>["steps"]
>[number];
type TaskReviewTool = NonNullable<
  NonNullable<TaskReviewJob["fast"]["runs"][number]["timeline"]>["tools"]
>[number];

function AssistantTimelineRow({ step }: { step: TaskReviewStep }) {
  return (
    <div className="flex gap-3 rounded-[18px] bg-white/70 px-3.5 py-3 ring-1 ring-black/[0.045]">
      <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-[var(--chat-accent-soft)] text-[var(--chat-accent)]">
        <Sparkles className="size-3.5" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-semibold text-[var(--chat-text-muted)]">
            助手回复 · 步骤 {step.ordinal}
          </span>
          <span className={cn("rounded-full px-2 py-0.5 text-[10px]", stepStatusTone(step.status))}>
            {STEP_STATUS_LABELS[step.status || ""] || "已记录"}
          </span>
        </div>
        <p className="mt-1.5 whitespace-pre-wrap break-words text-[13px] leading-6 text-[var(--chat-text)]">
          {step.content}
        </p>
      </div>
    </div>
  );
}

function ToolTimelineRow({ tool }: { tool: TaskReviewTool }) {
  return (
    <div
      className={cn(
        "rounded-[18px] p-3.5 ring-1",
        tool.failed
          ? "bg-red-500/[0.055] ring-red-500/10"
          : "bg-[var(--chat-surface-soft)]/80 ring-black/[0.035]"
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        {tool.failed ? (
          <XCircle className="size-3.5 text-red-600" />
        ) : (
          <Wrench className="size-3.5 text-[var(--chat-text-muted)]" />
        )}
        <span className="text-[13px] font-semibold text-[var(--chat-text)]">
          {tool.toolName}
        </span>
        <span className={cn("rounded-full px-2 py-0.5 text-[10px]", stepStatusTone(tool.status))}>
          {STEP_STATUS_LABELS[tool.status] || tool.status}
        </span>
        {tool.argumentBrief ? (
          <code className="min-w-0 max-w-full truncate rounded-md bg-white/80 px-1.5 py-0.5 text-[10px] text-[var(--chat-text-soft)] ring-1 ring-black/5">
            {tool.argumentBrief}
          </code>
        ) : null}
        {tool.durationMs != null ? (
          <span className="ml-auto text-[10px] text-[var(--chat-text-muted)]">
            {formatMs(tool.durationMs)}
          </span>
        ) : null}
      </div>
      {tool.errorMsg ? (
        <p className="mt-2 break-words text-[11px] leading-5 text-red-700">
          {tool.errorMsg}
        </p>
      ) : null}
      {tool.argumentsPreview || tool.resultPreview ? (
        <details className="mt-2 text-[11px] text-[var(--chat-text-muted)]">
          <summary className="cursor-pointer select-none">查看参数与结果</summary>
          {tool.argumentsPreview || tool.inputPreview ? (
            <pre className="mt-2 max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-white/85 p-2.5 font-mono text-[10px] leading-4 text-[var(--chat-text-soft)] ring-1 ring-black/5">
              {tool.argumentsPreview || tool.inputPreview}
            </pre>
          ) : null}
          {tool.resultPreview ? (
            <pre className="mt-2 max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-white/85 p-2.5 font-mono text-[10px] leading-4 text-[var(--chat-text-soft)] ring-1 ring-black/5">
              {tool.resultPreview}
            </pre>
          ) : null}
        </details>
      ) : null}
    </div>
  );
}

export function TaskReviewReport({ job }: { job: TaskReviewJob }) {
  const fast = job.fast;
  const completion = fast.completion;
  const statusLabel = CONCLUSION_LABELS[completion.conclusion] || completion.conclusion;

  return (
    <div className="space-y-4">
      <section className="overflow-hidden rounded-[32px] border border-black/[0.055] bg-white/90 shadow-[0_1px_2px_rgba(0,0,0,0.03),0_24px_70px_rgba(0,0,0,0.045)] backdrop-blur-xl">
        <div className="px-5 pb-5 pt-6 sm:px-8 sm:pb-8 sm:pt-8">
          <div className="flex flex-wrap items-center gap-2.5">
            <span
              className={cn(
                "rounded-full px-3 py-1 text-[12px] font-semibold ring-1",
                conclusionTone(completion.conclusion)
              )}
            >
              {statusLabel}
            </span>
            <span className="text-[12px] text-[var(--chat-text-muted)]">
              执行状态：{fast.execution.status || "UNKNOWN"}
            </span>
            {completion.advisory ? (
              <span className="rounded-full bg-black/[0.035] px-2 py-1 text-[10px] text-[var(--chat-text-muted)]">
                AI 推断
              </span>
            ) : null}
          </div>
          <h1 className="mt-4 max-w-3xl text-[25px] font-semibold leading-[1.25] tracking-[-0.035em] text-[var(--chat-text)] sm:text-[30px]">
            {completion.summary}
          </h1>
          <div className="mt-6 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
            <Metric icon={<Clock3 className="size-3.5" />} label="总耗时" value={formatMs(fast.execution.durationMs)} />
            <Metric icon={<Coins className="size-3.5" />} label="Token" value={formatNumber(fast.execution.totalTokens)} />
            <Metric icon={<Wrench className="size-3.5" />} label="工具调用" value={formatNumber(fast.execution.toolCount)} />
            <Metric icon={<ShieldAlert className="size-3.5" />} label="失败调用" value={formatNumber(fast.execution.failedToolCount)} />
          </div>
        </div>
      </section>

      <Panel
        title="轨迹体检"
        description="这些结论来自 Trace 和确定性规则，不依赖大模型。"
        icon={<ListChecks className="size-[18px]" />}
      >
        <div className="space-y-4">
          <div className="grid gap-2.5 sm:grid-cols-3">
            <Metric icon={<ListChecks className="size-3.5" />} label="产物" value={formatNumber(fast.execution.artifactCount)} />
            <Metric icon={<Clock3 className="size-3.5" />} label="重复调用" value={formatNumber(fast.metrics.repeatedCallExcess)} />
            <Metric icon={<ShieldAlert className="size-3.5" />} label="风险分" value={formatNumber(fast.metrics.riskScore)} />
          </div>
          {fast.findings.length ? (
            <div className="space-y-2">
              {fast.findings.map((finding, index) => (
                <FindingCard key={`${finding.code}-${index}`} finding={finding} />
              ))}
            </div>
          ) : (
            <div className="flex items-center gap-2 rounded-[18px] bg-emerald-500/10 p-3.5 text-[13px] text-emerald-800">
              <CheckCircle2 className="size-4" />
              没有发现确定性异常。
            </div>
          )}
          {fast.limitations.length ? (
            <div className="rounded-[18px] bg-[var(--chat-surface-soft)]/75 p-3.5 text-[13px] leading-5 text-[var(--chat-text-soft)]">
              <div className="font-medium text-[var(--chat-text)]">证据限制</div>
              <ul className="mt-1.5 list-disc space-y-1 pl-5">
                {fast.limitations.map((item) => <li key={item}>{item}</li>)}
              </ul>
            </div>
          ) : null}
        </div>
      </Panel>

      <QualitySection quality={job.quality} />

      <Panel
        title="执行时间线"
        description="只展示有正文的助手回复；没有正文的模型步骤不显示，但工具调用会完整保留。"
        icon={<Clock3 className="size-[18px]" />}
      >
        <div className="space-y-5">
          {fast.runs.map((run) => {
            const assistantSteps = (run.timeline?.steps || []).filter(
              (step) => Boolean(step.content?.trim())
            );
            const tools = run.timeline?.tools || [];
            const events = [
              ...assistantSteps.map((step) => ({
                key: `assistant:${step.id}`,
                order: step.ordinal,
                subOrder: 0,
                kind: "assistant" as const,
                step,
              })),
              ...tools.map((tool, index) => ({
                key: `tool:${tool.id}`,
                order:
                  typeof tool.stepNo === "number"
                    ? tool.stepNo
                    : assistantSteps.length + index + 1,
                subOrder:
                  typeof tool.dispatchIndex === "number"
                    ? tool.dispatchIndex
                    : index,
                kind: "tool" as const,
                tool,
              })),
            ].sort(
              (left, right) =>
                left.order - right.order ||
                left.subOrder - right.subOrder ||
                (left.kind === "assistant" ? 0 : 1) -
                  (right.kind === "assistant" ? 0 : 1)
            );
            return (
              <div key={run.requestId} className="space-y-3">
                <div className="flex flex-wrap items-center gap-2 px-1">
                  <span className="text-[13px] font-semibold text-[var(--chat-text)]">
                    {run.requestId}
                  </span>
                  <span className="rounded-full bg-black/[0.045] px-2 py-0.5 text-[10px] font-medium text-[var(--chat-text-muted)]">
                    {run.status}
                  </span>
                  <span className="text-[11px] text-[var(--chat-text-muted)]">
                    {formatMs(run.execution?.durationMs)} · {assistantSteps.length} 条助手回复 ·{" "}
                    {tools.length} 次工具调用
                  </span>
                </div>
                <div className="space-y-2">
                  {events.map((event) =>
                    event.kind === "assistant" ? (
                      <AssistantTimelineRow key={event.key} step={event.step} />
                    ) : (
                      <ToolTimelineRow key={event.key} tool={event.tool} />
                    )
                  )}
                </div>
                {run.finalText?.trim() ? (
                  <div className="rounded-[22px] border border-[var(--chat-accent)]/15 bg-[var(--chat-accent-soft)]/55 px-4 py-4">
                    <div className="flex items-center gap-2 text-[12px] font-semibold text-[var(--chat-accent)]">
                      <Sparkles className="size-3.5" />
                      最终回复
                    </div>
                    <p className="mt-2 whitespace-pre-wrap break-words text-[14px] leading-7 text-[var(--chat-text)]">
                      {run.finalText}
                    </p>
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      </Panel>
    </div>
  );
}
