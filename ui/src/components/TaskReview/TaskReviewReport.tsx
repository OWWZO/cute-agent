import { useState, type ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  CircleHelp,
  Clock3,
  Coins,
  GitBranch,
  ListChecks,
  ShieldAlert,
  Sparkles,
  Wrench,
  XCircle,
} from "lucide-react";
import type {
  TaskReviewDiagnosticSummary,
  TaskReviewEvidenceIndexEntry,
  TaskReviewFailureAttribution,
  TaskReviewFinding,
  TaskReviewJob,
  TaskReviewQuality,
} from "@/services/taskReview";
import { cn } from "@/lib/utils";
import {
  EvidenceInspectorDialog,
  type EvidencePreview,
} from "./EvidenceInspectorDialog";
import { NarrativeReportPanel } from "./NarrativeReportPanel";
import { ToolOutputBlock } from "@/components/Dialogue/tools/ToolOutputBlock";
import { ToolParametersBlock } from "@/components/Dialogue/tools/ToolParametersBlock";

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

const FAILURE_LAYER_LABELS: Record<string, string> = {
  MODEL_DECISION: "模型决策",
  MODEL_ARGUMENT: "模型参数",
  HARNESS_CONTRACT: "Harness 合约",
  HARNESS_RUNTIME: "Harness 运行时",
  ENVIRONMENT: "环境",
  USER_ACTION: "用户操作",
  TRACE_GAP: "Trace 缺口",
  UNKNOWN: "无法判断",
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

function EvidenceButton({
  evidenceRef,
  onOpenEvidence,
}: {
  evidenceRef: string;
  onOpenEvidence: (ref: string) => void;
}) {
  if (!evidenceRef.startsWith("tool:")) {
    return (
      <code className="rounded-md bg-white/80 px-1.5 py-1 text-[10px] text-[var(--chat-text-muted)] ring-1 ring-black/5">
        {evidenceRef}
      </code>
    );
  }
  return (
    <button
      type="button"
      onClick={() => onOpenEvidence(evidenceRef)}
      title="查看工具调用"
      className="inline-flex max-w-full items-center gap-1 truncate rounded-full bg-white/80 px-2 py-1 font-mono text-[10px] text-[var(--chat-text-muted)] ring-1 ring-black/5 transition hover:text-[var(--chat-accent)] hover:ring-[var(--chat-accent)]/20"
    >
      <span className="truncate">{evidenceRef}</span>
      <span aria-hidden="true">›</span>
    </button>
  );
}

function FindingCard({
  finding,
  onOpenEvidence,
}: {
  finding: TaskReviewFinding;
  onOpenEvidence: (ref: string) => void;
}) {
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
                <EvidenceButton
                  key={ref}
                  evidenceRef={ref}
                  onOpenEvidence={onOpenEvidence}
                />
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

function attributionTone(layer: string) {
  if (layer === "MODEL_DECISION" || layer === "MODEL_ARGUMENT") {
    return "bg-blue-500/10 text-blue-700 ring-blue-500/15";
  }
  if (layer === "HARNESS_CONTRACT" || layer === "HARNESS_RUNTIME") {
    return "bg-amber-500/10 text-amber-700 ring-amber-500/15";
  }
  if (layer === "ENVIRONMENT") {
    return "bg-orange-500/10 text-orange-700 ring-orange-500/15";
  }
  if (layer === "USER_ACTION") {
    return "bg-black/[0.045] text-[var(--chat-text-soft)] ring-black/5";
  }
  return "bg-red-500/[0.07] text-red-700 ring-red-500/10";
}

function FailureAttributionSection({
  attributions,
  summary,
  onOpenEvidence,
}: {
  attributions: TaskReviewFailureAttribution[];
  summary?: TaskReviewDiagnosticSummary;
  onOpenEvidence: (ref: string) => void;
}) {
  const layerCounts = summary?.layerCounts || {};
  return (
    <Panel
      title="失败与责任归因"
      description="逐次失败区分模型决策、参数、Harness、环境和 Trace 缺口；证据不足时不硬猜。"
      icon={<GitBranch className="size-[18px]" />}
    >
      <div className="space-y-4">
        <div className="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
          <Metric
            icon={<AlertTriangle className="size-3.5" />}
            label="失败调用"
            value={formatNumber(summary?.totalFailures ?? attributions.length)}
          />
          <Metric
            icon={<Wrench className="size-3.5" />}
            label="参数问题"
            value={formatNumber(summary?.parameterIssueCount ?? 0)}
          />
          <Metric
            icon={<CheckCircle2 className="size-3.5" />}
            label="已恢复"
            value={formatNumber(summary?.recoveredCount ?? 0)}
          />
          <Metric
            icon={<XCircle className="size-3.5" />}
            label="未恢复"
            value={formatNumber(summary?.unrecoveredCount ?? 0)}
          />
        </div>
        {Object.values(layerCounts).some((count) => Number(count) > 0) ? (
          <div className="flex flex-wrap gap-2">
            {Object.entries(layerCounts)
              .filter(([, count]) => Number(count) > 0)
              .map(([layer, count]) => (
                <span
                  key={layer}
                  className={cn(
                    "rounded-full px-2.5 py-1 text-[11px] font-medium ring-1",
                    attributionTone(layer)
                  )}
                >
                  {FAILURE_LAYER_LABELS[layer] || layer} · {count}
                </span>
              ))}
          </div>
        ) : null}
        {attributions.length ? (
          <div className="space-y-2.5">
            {attributions.map((attribution) => (
              <div
                key={attribution.attributionId}
                className="rounded-[20px] bg-[var(--chat-surface-soft)]/75 p-4 ring-1 ring-black/[0.035]"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[14px] font-semibold text-[var(--chat-text)]">
                    {attribution.toolName}
                  </span>
                  <span
                    className={cn(
                      "rounded-full px-2 py-0.5 text-[10px] font-medium ring-1",
                      attributionTone(String(attribution.primaryLayer))
                    )}
                  >
                    {FAILURE_LAYER_LABELS[attribution.primaryLayer] ||
                      attribution.primaryLayer}
                  </span>
                  <span
                    className={cn(
                      "rounded-full px-2 py-0.5 text-[10px]",
                      attribution.recovered
                        ? "bg-emerald-500/10 text-emerald-700"
                        : "bg-red-500/10 text-red-700"
                    )}
                  >
                    {attribution.recovered ? "已恢复" : "未恢复"}
                  </span>
                  <span className="rounded-full bg-white/75 px-2 py-0.5 text-[10px] text-[var(--chat-text-muted)] ring-1 ring-black/[0.045]">
                    {attribution.source === "deterministic+judge"
                      ? "事实 + AI 复核"
                      : attribution.source === "judge"
                        ? "AI 归因"
                        : "确定性归因"}
                  </span>
                  {attribution.step != null ? (
                    <span className="text-[10px] text-[var(--chat-text-muted)]">
                      步骤 {attribution.step}
                    </span>
                  ) : null}
                  <span className="ml-auto text-[10px] text-[var(--chat-text-muted)]">
                    置信度 {Math.round(attribution.confidence * 100)}%
                  </span>
                </div>
                <p className="mt-2 text-[13px] leading-5 text-[var(--chat-text-soft)]">
                  {attribution.reason}
                </p>
                {attribution.recoverySummary ? (
                  <p className="mt-1.5 text-[12px] leading-5 text-[var(--chat-text-muted)]">
                    恢复情况：{attribution.recoverySummary}
                  </p>
                ) : null}
                {attribution.contributingLayers.length ? (
                  <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[10px] text-[var(--chat-text-muted)]">
                    <span>贡献层</span>
                    {attribution.contributingLayers.map((layer) => (
                      <span
                        key={layer}
                        className="rounded-md bg-white/80 px-1.5 py-0.5 ring-1 ring-black/5"
                      >
                        {FAILURE_LAYER_LABELS[layer] || layer}
                      </span>
                    ))}
                  </div>
                ) : null}
                {attribution.evidenceRefs.length ? (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {attribution.evidenceRefs.map((ref) => (
                      <EvidenceButton
                        key={ref}
                        evidenceRef={ref}
                        onOpenEvidence={onOpenEvidence}
                      />
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        ) : (
          <div className="flex items-center gap-2 rounded-[18px] bg-emerald-500/10 p-3.5 text-[13px] text-emerald-800">
            <CheckCircle2 className="size-4" />
            没有记录失败工具调用，因此没有生成责任归因。
          </div>
        )}
      </div>
    </Panel>
  );
}

function QualitySection({
  quality,
  onOpenEvidence,
}: {
  quality: TaskReviewQuality;
  onOpenEvidence: (ref: string) => void;
}) {
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
                    {dimension.applicable === false
                      ? "本次不适用"
                      : LEVEL_LABELS[dimension.level] || dimension.level}
                  </span>
                </div>
                <p className="mt-2 text-[13px] leading-5 text-[var(--chat-text-soft)]">
                  {dimension.applicable === false
                    ? dimension.notApplicableReason || "当前任务没有适用证据。"
                    : dimension.reason}
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
              <FindingCard
                key={`${finding.code}-${finding.title}`}
                finding={finding}
                onOpenEvidence={onOpenEvidence}
              />
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

function ToolTimelineRow({
  tool,
  evidenceRef,
  onOpenEvidence,
}: {
  tool: TaskReviewTool;
  evidenceRef: string;
  onOpenEvidence: (ref: string) => void;
}) {
  return (
    <div
      id={`tool-${tool.toolCallId || tool.id}`}
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
        <button
          type="button"
          onClick={() => onOpenEvidence(evidenceRef)}
          className="rounded-full bg-white/80 px-2 py-0.5 text-[9px] font-medium text-[var(--chat-text-muted)] ring-1 ring-black/5 transition hover:text-[var(--chat-accent)]"
        >
          完整证据
        </button>
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
            <div className="mt-2">
              <ToolParametersBlock text={tool.argumentsPreview || tool.inputPreview} />
            </div>
          ) : null}
          {tool.resultPreview ? (
            <div className="mt-2">
              <ToolOutputBlock lines={tool.resultPreview.split(/\r?\n/)} />
            </div>
          ) : null}
        </details>
      ) : null}
    </div>
  );
}

function fallbackEvidenceIndex(
  job: TaskReviewJob
): Record<string, TaskReviewEvidenceIndexEntry> {
  const result: Record<string, TaskReviewEvidenceIndexEntry> = {};
  for (const run of job.fast.runs) {
    for (const tool of run.timeline?.tools || []) {
      const ref =
        tool.evidenceRef || `tool:${run.requestId}:${tool.id || tool.toolCallId}`;
      result[ref] = {
        requestId: run.requestId,
        runId: run.requestId,
        toolCallId: tool.toolCallId || tool.id,
        toolName: tool.toolName,
        step: tool.stepNo,
        status: tool.status,
        failed: Boolean(tool.failed),
      };
    }
  }
  return result;
}

function evidencePreview(job: TaskReviewJob, ref: string): EvidencePreview | undefined {
  const parts = ref.slice("tool:".length).split(":");
  const requestId = parts.length > 1 ? parts[0] : undefined;
  const toolCallId = parts.length > 1 ? parts[1] : parts[0];
  for (const run of job.fast.runs) {
    if (requestId && run.requestId !== requestId) continue;
    const tool = (run.timeline?.tools || []).find(
      (item) => item.id === toolCallId || item.toolCallId === toolCallId
    );
    if (!tool) continue;
    return {
      toolName: tool.toolName,
      step: tool.stepNo,
      status: tool.status,
      argumentsPreview: tool.argumentsPreview || tool.inputPreview,
      resultPreview: tool.resultPreview,
      errorMsg: tool.errorMsg,
    };
  }
  return undefined;
}

export function TaskReviewReport({ job }: { job: TaskReviewJob }) {
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [selectedEvidenceRef, setSelectedEvidenceRef] = useState<string | null>(
    null
  );
  const fast = job.fast;
  const completion = fast.completion;
  const statusLabel = CONCLUSION_LABELS[completion.conclusion] || completion.conclusion;
  const diagnostics =
    job.quality.diagnostics ||
    job.diagnostics || {
      failureAttributions: job.quality.failureAttributions || [],
      diagnosticSummary: job.quality.diagnosticSummary,
    };
  const narrative = job.narrative || job.quality.narrative;
  const evidenceIndex = {
    ...fallbackEvidenceIndex(job),
    ...(job.evidenceIndex || {}),
  };
  const selectedIndex = selectedEvidenceRef
    ? evidenceIndex[selectedEvidenceRef]
    : undefined;
  const selectedPreview = selectedEvidenceRef
    ? evidencePreview(job, selectedEvidenceRef)
    : undefined;
  const openEvidence = (ref: string) => {
    setSelectedEvidenceRef(ref);
    setEvidenceOpen(true);
  };

  return (
    <div className="space-y-4">
      {job.scope === "tree" ? (
        <section className="rounded-[22px] border border-black/[0.055] bg-white/75 px-4 py-3.5">
          <div className="flex items-center gap-2 text-[12px] font-semibold text-[var(--chat-text)]">
            <GitBranch className="size-4" />
            已按整棵 Agent 树复盘
          </div>
          <p className="mt-1.5 text-[11px] leading-5 text-[var(--chat-text-muted)]">
            本次 Trace 覆盖主会话与全部子会话；报告中的执行事实和证据引用均来自整棵树。
          </p>
        </section>
      ) : null}
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

      {narrative ? (
        <NarrativeReportPanel
          narrative={narrative}
          jobId={job.jobId}
          onOpenEvidence={openEvidence}
        />
      ) : null}

      <FailureAttributionSection
        attributions={diagnostics.failureAttributions || []}
        summary={diagnostics.diagnosticSummary}
        onOpenEvidence={openEvidence}
      />

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
                <FindingCard
                  key={`${finding.code}-${index}`}
                  finding={finding}
                  onOpenEvidence={openEvidence}
                />
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

      <QualitySection quality={job.quality} onOpenEvidence={openEvidence} />

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
                      <ToolTimelineRow
                        key={event.key}
                        tool={event.tool}
                        evidenceRef={
                          event.tool.evidenceRef ||
                          `tool:${run.requestId}:${
                            event.tool.id || event.tool.toolCallId
                          }`
                        }
                        onOpenEvidence={openEvidence}
                      />
                    )
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </Panel>

      <EvidenceInspectorDialog
        open={evidenceOpen}
        onOpenChange={setEvidenceOpen}
        jobId={job.jobId}
        evidenceRef={selectedEvidenceRef}
        indexEntry={selectedIndex}
        preview={selectedPreview}
        onNavigateRef={openEvidence}
      />
    </div>
  );
}
