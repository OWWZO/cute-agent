import { useState } from "react";
import {
  AlertTriangle,
  Check,
  ChevronDown,
  ClipboardCopy,
  Code2,
  FlaskConical,
  ListChecks,
  Wrench,
} from "lucide-react";
import { toast } from "sonner";
import type { TaskReviewDeveloperAction } from "@/services/taskReview";
import { cn } from "@/lib/utils";

const SCOPE_LABELS: Record<string, string> = {
  PRODUCT_RUNTIME: "产品运行时",
  TOOL_IMPLEMENTATION: "工具实现",
  MODEL_POLICY: "模型策略",
  ENVIRONMENT_CONFIG: "环境配置",
  TRACE_INSTRUMENTATION: "Trace 埋点",
  USER_WORKSPACE: "用户工作区",
  NO_ACTION: "无需修复",
};

function priorityTone(priority: string) {
  if (priority === "P0") return "bg-red-500/10 text-red-700 ring-red-500/15";
  if (priority === "P1") return "bg-amber-500/10 text-amber-700 ring-amber-500/15";
  return "bg-black/[0.045] text-[var(--chat-text-soft)] ring-black/5";
}

export function DeveloperActionCard({
  action,
  onOpenEvidence,
}: {
  action: TaskReviewDeveloperAction;
  onOpenEvidence: (ref: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [promptOpen, setPromptOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const frequency = action.frequency || {
    count: 0,
    recovered: 0,
    unrecovered: 0,
  };
  const problem = action.problemDefinition;
  const reproduction = action.reproduction;
  const root = action.rootCauseHypothesis;
  const testPlan = action.testPlan;
  const title = action.title || action.action || "待定位的修复任务";
  const needsEvidence = action.status === "needs_more_evidence";

  const copyPrompt = async () => {
    if (!action.agentPrompt) return;
    try {
      await navigator.clipboard.writeText(action.agentPrompt);
      setCopied(true);
      toast.success("修复任务已复制");
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      toast.error("复制失败，请展开 Prompt 后手动选择");
    }
  };

  return (
    <article
      className={cn(
        "overflow-hidden rounded-[22px] border bg-white/78 ring-1",
        needsEvidence
          ? "border-amber-500/15 ring-amber-500/5"
          : "border-black/[0.055] ring-black/[0.025]"
      )}
    >
      <button
        type="button"
        onClick={() => setExpanded((value) => !value)}
        className="flex w-full items-start gap-3 px-4 py-4 text-left transition hover:bg-black/[0.018]"
      >
        <span
          className={cn(
            "mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full",
            needsEvidence
              ? "bg-amber-500/10 text-amber-700"
              : "bg-[var(--chat-accent-soft)] text-[var(--chat-accent)]"
          )}
        >
          {needsEvidence ? (
            <AlertTriangle className="size-4" />
          ) : (
            <Wrench className="size-4" />
          )}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span
              className={cn(
                "rounded-full px-2 py-0.5 text-[10px] font-semibold ring-1",
                priorityTone(action.priority)
              )}
            >
              {action.priority}
            </span>
            <span className="text-[14px] font-semibold leading-5 text-[var(--chat-text)]">
              {title}
            </span>
            {needsEvidence ? (
              <span className="rounded-full bg-amber-500/10 px-2 py-0.5 text-[9px] font-medium text-amber-700">
                需补证据
              </span>
            ) : null}
          </span>
          <span className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[10px] text-[var(--chat-text-muted)]">
            <span>工具 {action.toolName || "unknown"}</span>
            <span>·</span>
            <span>{action.failureKind || "未分类失败"}</span>
            <span>·</span>
            <span>{SCOPE_LABELS[action.actionScope] || action.actionScope}</span>
            <span>·</span>
            <span>
              {frequency.count} 次，未恢复 {frequency.unrecovered} 次
            </span>
          </span>
        </span>
        <ChevronDown
          className={cn(
            "mt-1 size-4 shrink-0 text-[var(--chat-text-muted)] transition-transform",
            expanded && "rotate-180"
          )}
        />
      </button>

      {expanded ? (
        <div className="border-t border-black/[0.045] px-4 pb-4 pt-4">
          {problem ? (
            <div className="grid gap-2 md:grid-cols-3">
              <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3.5 py-3">
                <div className="text-[10px] font-medium text-[var(--chat-text-muted)]">
                  观测现象
                </div>
                <p className="mt-1 text-[12px] leading-5 text-[var(--chat-text-soft)]">
                  {problem.observed}
                </p>
              </div>
              <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3.5 py-3">
                <div className="text-[10px] font-medium text-[var(--chat-text-muted)]">
                  期望行为
                </div>
                <p className="mt-1 text-[12px] leading-5 text-[var(--chat-text-soft)]">
                  {problem.expected}
                </p>
              </div>
              <div className="rounded-[16px] bg-[var(--chat-surface-soft)] px-3.5 py-3">
                <div className="text-[10px] font-medium text-[var(--chat-text-muted)]">
                  影响
                </div>
                <p className="mt-1 text-[12px] leading-5 text-[var(--chat-text-soft)]">
                  {problem.impact}
                </p>
              </div>
            </div>
          ) : null}

          <div className="mt-4 grid gap-3 lg:grid-cols-2">
            <section className="rounded-[18px] bg-[var(--chat-surface-soft)]/75 p-3.5">
              <h4 className="flex items-center gap-1.5 text-[12px] font-semibold text-[var(--chat-text)]">
                <FlaskConical className="size-3.5" />
                最小复现
              </h4>
              <ol className="mt-2 list-decimal space-y-1.5 pl-5 text-[11px] leading-5 text-[var(--chat-text-soft)]">
                {(reproduction?.steps || []).map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ol>
              {reproduction?.expectedFailure ? (
                <p className="mt-2 rounded-xl bg-white/70 px-2.5 py-2 text-[10px] leading-4 text-[var(--chat-text-muted)]">
                  预期失败：{reproduction.expectedFailure}
                </p>
              ) : null}
            </section>

            <section className="rounded-[18px] bg-[var(--chat-surface-soft)]/75 p-3.5">
              <h4 className="flex items-center gap-1.5 text-[12px] font-semibold text-[var(--chat-text)]">
                <Code2 className="size-3.5" />
                根因假设
              </h4>
              <p className="mt-2 text-[11px] leading-5 text-[var(--chat-text-soft)]">
                {root?.summary || "当前证据不足，暂不猜测根因。"}
              </p>
              <div className="mt-2 text-[10px] text-[var(--chat-text-muted)]">
                置信度 {Math.round(Number(root?.confidence || 0) * 100)}%
              </div>
            </section>
          </div>

          <section className="mt-3 rounded-[18px] bg-[var(--chat-surface-soft)]/75 p-3.5">
            <h4 className="flex items-center gap-1.5 text-[12px] font-semibold text-[var(--chat-text)]">
              <Wrench className="size-3.5" />
              修复步骤
            </h4>
            <div className="mt-2 space-y-2">
              {(action.fixPlan || []).map((item) => (
                <div key={`${item.order}-${item.summary}`} className="flex gap-2.5">
                  <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-white/80 text-[9px] font-semibold text-[var(--chat-text-muted)] ring-1 ring-black/5">
                    {item.order}
                  </span>
                  <p className="text-[11px] leading-5 text-[var(--chat-text-soft)]">
                    <span className="font-medium text-[var(--chat-text)]">
                      {item.summary}：
                    </span>
                    {item.detail}
                  </p>
                </div>
              ))}
            </div>
          </section>

          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <section className="rounded-[18px] bg-[var(--chat-surface-soft)]/75 p-3.5">
              <h4 className="flex items-center gap-1.5 text-[12px] font-semibold text-[var(--chat-text)]">
                <ListChecks className="size-3.5" />
                测试与验收
              </h4>
              <div className="mt-2 space-y-1.5 text-[10px] leading-5 text-[var(--chat-text-soft)]">
                {(testPlan?.unit || []).map((item) => (
                  <p key={`unit-${item}`}>单元 · {item}</p>
                ))}
                {(testPlan?.integration || []).map((item) => (
                  <p key={`integration-${item}`}>集成 · {item}</p>
                ))}
                {(testPlan?.regression || []).map((item) => (
                  <p key={`regression-${item}`}>回归 · {item}</p>
                ))}
              </div>
              <div className="mt-3 border-t border-black/[0.045] pt-2">
                {(action.acceptanceCriteria || []).map((item) => (
                  <p
                    key={item}
                    className="flex gap-1.5 text-[10px] leading-5 text-[var(--chat-text-soft)]"
                  >
                    <Check className="mt-1 size-2.5 shrink-0 text-emerald-600" />
                    {item}
                  </p>
                ))}
              </div>
            </section>

            <section className="rounded-[18px] bg-[var(--chat-surface-soft)]/75 p-3.5">
              <h4 className="text-[12px] font-semibold text-[var(--chat-text)]">
                关联证据
              </h4>
              <div className="mt-2 flex flex-wrap gap-1.5">
                {(action.evidenceRefs || []).map((ref) => (
                  <button
                    key={ref}
                    type="button"
                    onClick={() => onOpenEvidence(ref)}
                    className="max-w-full truncate rounded-full bg-white/80 px-2.5 py-1 font-mono text-[9px] text-[var(--chat-text-soft)] ring-1 ring-black/5 transition hover:text-[var(--chat-accent)]"
                  >
                    {ref}
                  </button>
                ))}
              </div>
            </section>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => void copyPrompt()}
              disabled={!action.agentPrompt}
              className="inline-flex items-center gap-1.5 rounded-full bg-[var(--chat-text)] px-3 py-2 text-[11px] font-medium text-white transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {copied ? (
                <Check className="size-3.5" />
              ) : (
                <ClipboardCopy className="size-3.5" />
              )}
              {copied ? "已复制" : "复制修复任务"}
            </button>
            <button
              type="button"
              onClick={() => setPromptOpen((value) => !value)}
              className="inline-flex items-center gap-1.5 rounded-full bg-white/80 px-3 py-2 text-[11px] font-medium text-[var(--chat-text-soft)] ring-1 ring-black/[0.055] transition hover:text-[var(--chat-accent)]"
            >
              <Code2 className="size-3.5" />
              {promptOpen ? "收起完整 Prompt" : "展开完整 Prompt"}
            </button>
          </div>

          {promptOpen ? (
            <pre className="mt-3 max-h-[48vh] overflow-auto whitespace-pre-wrap break-words rounded-[18px] bg-[#171717] p-4 font-mono text-[10px] leading-5 text-white/90">
              {action.agentPrompt || "当前没有生成可复制的修复任务。"}
            </pre>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}
