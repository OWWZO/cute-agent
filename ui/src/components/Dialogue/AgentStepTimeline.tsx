import {
  FC,
  memo,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { motion, AnimatePresence } from "motion/react";
import { ChevronDownIcon } from "lucide-react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  resolveDeepSearchStage,
  shouldRenderDeepSearchPreview,
} from "@/utils/deepSearch";
import { cn } from "@/lib/utils";
import { DeepSearchPreviewItem, ToolItem } from "./Timeline";
import {
  buildAgentProcessSignature,
  buildAgentProcessViewModel,
  deriveAgentProcessTaskModel,
  type ProcessSegment,
  type ProcessStepGroup,
  type ProcessStepRow,
  resolveAssistantReplyText,
} from "./agentProcessModel";
import { useNowTicker } from "@/components/ChatView/subAgentsModel";
import UserBriefCard from "./UserBriefCard";
import MarkdownRenderer from "@/components/ActionPanel/MarkdownRenderer";
import { resolveTaskSummaryText } from "./contentHelpers";
import { ThinkingBlock } from "./ThinkingBlock";
import {
  isSubAgentParentTask,
  isRunInBackgroundAgent,
} from "@/utils/chat/subagent";
import { isParentLoopLive } from "@/components/ChatView/streamState";
import { useToolEntranceAnimation } from "./tools/useToolEntranceAnimation";
import { resolveTaskResultMap } from "@/utils/chat/toolCalls";
import {
  ToolCallView,
  ToolGroup,
  resolveStackPosition,
} from "./tools";

type AgentStepTimelineProps = {
  chat: CHAT.ChatItem;
  isPlanSolveMessage: boolean;
  thoughtText?: string;
  thoughtStreaming?: boolean;
  thoughtVersionLabel?: string;
  thoughtVersionIndex?: number;
  thoughtVersionTotal?: number;
  onThoughtPrev?: () => void;
  onThoughtNext?: () => void;
  canThoughtPrev?: boolean;
  canThoughtNext?: boolean;
  planSlot?: ReactNode;
  changeActiveChat: CHAT.OpenTaskHandler;
  changePlan?: () => void;
  changeFile?: CHAT.OpenFileHandler;
  onOpenToolDiff?: (task: CHAT.Task, chat: CHAT.ChatItem, fileIndex?: number) => void;
  onOpenAgent?: (task: CHAT.Task, chat: CHAT.ChatItem) => void;
};

const RICH_INLINE_TYPES = new Set([
  "ask_user_question",
  "desktop_control",
  "plan_approval",
  "session_tasks",
  "browser",
  "task_summary",
]);

function asText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeComparableText(value: string): string {
  return value
    // 保留代码围栏内部源码；纯代码回复也需要参与终答去重比较。
    .replace(/```[^\r\n]*\r?\n([\s\S]*?)```/g, "$1")
    .replace(/[`*_#>()]/g, " ")
    .replace(/\[/g, " ")
    .replace(/\]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function textsNearlyEqual(
  left: string,
  right: string,
  normalizedRight = normalizeComparableText(right)
): boolean {
  const a = normalizeComparableText(left);
  const b = normalizedRight;
  if (!a || !b) {
    return false;
  }
  if (a === b) {
    return true;
  }
  // 终答流式/结果覆盖时文案可能前后缀略有差异
  const minLen = Math.min(a.length, b.length);
  if (minLen < 24) {
    return false;
  }
  return a.includes(b) || b.includes(a);
}

function resolveSegmentComparableText(segment: ProcessSegment): string {
  if (segment.type === "assistant_reply") {
    return segment.text;
  }
  if (segment.type === "thinking") {
    return asText(segment.step.tool.toolThought);
  }
  if (segment.type === "group") {
    // 组内 task_summary / 仅一条与终答同文的过程回复
    for (const step of segment.group.steps) {
      if (step.tool.messageType === "task_summary") {
        return resolveTaskSummaryText(step.tool);
      }
      if (step.kind === "assistant_reply") {
        // agent_stream 的正文在 result 字段，tool_thought 才在 toolThought
        return resolveAssistantReplyText(step.tool);
      }
    }
  }
  return "";
}

/** 与底部 conclusion 同文案的时间线条目视为重复（终答不当思考/过程） */
// eslint-disable-next-line react-refresh/only-export-components
export function isDuplicateOfConclusion(
  segment: ProcessSegment,
  conclusionText: string,
  normalizedConclusion = normalizeComparableText(conclusionText)
): boolean {
  if (!normalizedConclusion) {
    return false;
  }
  // task_summary 若已作为底部结论，不再在时间线重复
  if (
    segment.type === "group" &&
    segment.group.steps.every((step) => step.tool.messageType === "task_summary")
  ) {
    const summary = resolveTaskSummaryText(segment.group.steps[0]?.tool);
    return !summary || textsNearlyEqual(summary, conclusionText, normalizedConclusion);
  }
  const segmentText = resolveSegmentComparableText(segment);
  return textsNearlyEqual(segmentText, conclusionText, normalizedConclusion);
}

function isAgentStep(step: ProcessStepRow): boolean {
  return (
    step.kind === "agent" ||
    Boolean(step.tool.children?.length) ||
    isSubAgentParentTask(step.tool)
  );
}

function isRichInlineStep(step: ProcessStepRow): boolean {
  const messageType = step.tool.messageType || "";
  if (RICH_INLINE_TYPES.has(messageType)) {
    return true;
  }
  if (isAgentStep(step)) {
    return true;
  }
  if (messageType === "deep_search") {
    return true;
  }
  return false;
}

const DurationBadge: FC<{ label?: string; active?: boolean }> = ({
  label,
  active,
}) => {
  if (!label && !active) {
    return null;
  }
  return (
    <span
      className={cn(
        "timeline-duration ml-auto shrink-0 text-ui-caption font-normal text-[var(--chat-text-muted)]"
      )}
    >
      {active && !label ? "…" : label}
    </span>
  );
};

/** 多轮规划思考版本切换，嵌在 ThinkingBlock 标题行 */
const ThoughtVersionSwitcher: FC<{
  versionLabel: string;
  onPrev?: () => void;
  onNext?: () => void;
  canPrev?: boolean;
  canNext?: boolean;
}> = ({ versionLabel, onPrev, onNext, canPrev, canNext }) => (
  <div className="inline-flex items-center gap-0.5 rounded-full bg-[var(--chat-surface-soft)] px-1.5 py-0.5 text-ui-xs text-[var(--chat-text-muted)]">
    <button
      type="button"
      className="rounded px-0.5 disabled:opacity-30"
      onClick={(event) => {
        event.stopPropagation();
        onPrev?.();
      }}
      disabled={!canPrev}
      aria-label="上一版思考"
    >
      {"<"}
    </button>
    <span>{versionLabel}</span>
    <button
      type="button"
      className="rounded px-0.5 disabled:opacity-30"
      onClick={(event) => {
        event.stopPropagation();
        onNext?.();
      }}
      disabled={!canNext}
      aria-label="下一版思考"
    >
      {">"}
    </button>
  </div>
);

type StepRowContext = {
  chat: CHAT.ChatItem;
  runLive: boolean;
  changeActiveChat: CHAT.OpenTaskHandler;
  changePlan?: () => void;
  changeFile?: CHAT.OpenFileHandler;
  onOpenToolDiff?: (task: CHAT.Task, chat: CHAT.ChatItem, fileIndex?: number) => void;
  onOpenAgent?: (task: CHAT.Task, chat: CHAT.ChatItem) => void;
};

const TimelineEntrance: FC<{
  entryKey: string;
  active: boolean;
  className?: string;
  children: ReactNode;
}> = memo(({ entryKey, active, className, children }) => {
  const shouldAnimate = useToolEntranceAnimation(entryKey, active);
  return (
    <motion.div
      initial={shouldAnimate ? { opacity: 0, y: 4 } : false}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
      className={className}
    >
      {children}
    </motion.div>
  );
});

TimelineEntrance.displayName = "TimelineEntrance";

const CompactStepRow: FC<{
  step: ProcessStepRow;
  ctx: StepRowContext;
}> = memo(({ step, ctx }) => {
  const isThought = step.kind === "thinking";
  const thoughtText = asText(step.tool.toolThought);
  const rich = isRichInlineStep(step);

  // 组内原生 CoT：标题点击折叠，流式完自动收起
  if (isThought) {
    return (
      <div className="py-0.5 pl-1" data-testid="llm-reasoning-step">
        <ThinkingBlock
          text={thoughtText || "…"}
          streaming={step.active}
          autoCollapseOnStreamEnd={!ctx.runLive}
          durationMs={step.durationMs}
          durationLabel={step.durationLabel}
        />
      </div>
    );
  }

  if (step.kind === "assistant_reply") {
    const text = thoughtText;
    if (!text) {
      return null;
    }
    return (
      <div className="timeline-assistant-reply px-1 py-1 pl-7">
        {text}
      </div>
    );
  }

  // 子 Agent：时间线只渲染 Bot 摘要行（同步 / 后台同卡）。
  // 运行细节统一从右上角「智能体」面板与右侧工作区的子智能体 tab 看，
  // 主时间线不再铺第二层过程卡。
  if (isAgentStep(step)) {
    return (
      <div className="py-0.5">
        <ToolCallView
          tool={step.tool}
          chat={ctx.chat}
          streaming={ctx.chat.loading}
          changeActiveChat={ctx.changeActiveChat}
          changeFile={ctx.changeFile}
          changePlan={ctx.changePlan}
          onOpenAgent={ctx.onOpenAgent}
          onOpenToolDiff={ctx.onOpenToolDiff}
        />
      </div>
    );
  }

  if (rich) {
    const stage =
      step.tool.messageType === "deep_search"
        ? resolveDeepSearchStage(resolveTaskResultMap(step.tool).messageType)
        : undefined;
    const showDeepSearchPreview =
      step.tool.messageType === "deep_search" &&
      shouldRenderDeepSearchPreview(stage);

    return (
      <div className="relative py-0.5">
        <div className="pointer-events-none absolute right-1 top-3 z-[1]">
          <DurationBadge label={step.durationLabel} active={step.active} />
        </div>
        {showDeepSearchPreview ? (
          <DeepSearchPreviewItem
            tool={step.tool}
            chat={ctx.chat}
            changeActiveChat={ctx.changeActiveChat}
          />
        ) : (
          <ToolItem
            tool={step.tool}
            chat={ctx.chat}
            changePlan={ctx.changePlan}
            changeActiveChat={ctx.changeActiveChat}
            changeFile={ctx.changeFile}
          />
        )}
      </div>
    );
  }

  return (
    <div className="py-0.5">
      <ToolCallView
        tool={step.tool}
        chat={ctx.chat}
        streaming={ctx.chat.loading}
        changeActiveChat={ctx.changeActiveChat}
        changeFile={ctx.changeFile}
        changePlan={ctx.changePlan}
        onOpenToolDiff={ctx.onOpenToolDiff}
        onOpenAgent={ctx.onOpenAgent}
      />
    </div>
  );
});

CompactStepRow.displayName = "CompactStepRow";

const StepGroupBlock: FC<{
  group: ProcessStepGroup;
  defaultOpen: boolean;
  forceOpen: boolean;
  ctx: StepRowContext;
}> = memo(({ group, defaultOpen, forceOpen, ctx }) => {
  const [open, setOpen] = useState(defaultOpen);
  const canCollapse = group.collapsible !== false && group.stepCount > 0;
  const wasForcedRef = useRef(false);

  useEffect(() => {
    const shouldForce = forceOpen || group.active;
    if (shouldForce && !wasForcedRef.current) {
      setOpen(true);
    }
    wasForcedRef.current = shouldForce;
  }, [forceOpen, group.active]);

  const toolCardSteps = group.steps.filter(
    (step) =>
      step.kind !== "thinking" &&
      step.kind !== "assistant_reply" &&
      !isRichInlineStep(step) &&
      !isRunInBackgroundAgent(step.tool)
  );
  const allToolCards =
    toolCardSteps.length > 0 && toolCardSteps.length === group.steps.length;

  if (allToolCards) {
    return (
      <div className="w-full py-0.5">
        <ToolGroup
          title={group.title}
          count={toolCardSteps.length}
          defaultOpen={forceOpen || open || group.active || !group.completed}
        >
          {toolCardSteps.map((step, index) => (
            <ToolCallView
              key={step.id}
              tool={step.tool}
              chat={ctx.chat}
              stackPosition={resolveStackPosition(index, toolCardSteps.length)}
              streaming={ctx.chat.loading}
              changeActiveChat={ctx.changeActiveChat}
              changeFile={ctx.changeFile}
              changePlan={ctx.changePlan}
              onOpenToolDiff={ctx.onOpenToolDiff}
              onOpenAgent={ctx.onOpenAgent}
            />
          ))}
        </ToolGroup>
      </div>
    );
  }

  const stepsBody = (
    <div className="timeline-rail relative ml-[7px] pl-3.5">
      <AnimatePresence initial={false}>
        {group.steps.map((step) => (
          <TimelineEntrance
            key={step.id}
            entryKey={step.id}
            active={ctx.runLive}
          >
            <CompactStepRow step={step} ctx={ctx} />
          </TimelineEntrance>
        ))}
      </AnimatePresence>
    </div>
  );

  if (!canCollapse) {
    return <div className="w-full">{stepsBody}</div>;
  }

  return (
    <Collapsible
      open={open}
      onOpenChange={setOpen}
      className="w-full"
    >
      <CollapsibleTrigger
        className={cn(
          "group flex w-full items-center gap-2 py-1.5 text-left transition-colors",
          "text-[var(--chat-text-muted)] hover:text-[var(--chat-text)]"
        )}
      >
        <span
          className={cn(
            "timeline-group-title min-w-0 flex-1 truncate text-ui-base font-medium",
            group.active && "thinking-shimmer"
          )}
        >
          {group.title}
        </span>
        {group.digitalEmployee ? (
          <span className="hidden shrink-0 text-ui-sm text-[var(--chat-text-muted)] sm:inline">
            {group.digitalEmployee}
          </span>
        ) : null}
        <motion.div
          animate={{ rotate: open ? 180 : 0 }}
          transition={{
            duration: 0.2,
            ease: [0.25, 0.46, 0.45, 0.94],
          }}
          className="shrink-0"
        >
          <ChevronDownIcon className="size-3.5 text-current opacity-70" />
        </motion.div>
        <DurationBadge label={group.durationLabel} active={group.active} />
      </CollapsibleTrigger>

      <CollapsibleContent>{stepsBody}</CollapsibleContent>
    </Collapsible>
  );
});

StepGroupBlock.displayName = "StepGroupBlock";

const UserMessageSegment: FC<{ step: ProcessStepRow }> = memo(({ step }) => (
  <div className="my-2 w-full" data-testid="process-user-message">
    <UserBriefCard tool={step.tool} />
  </div>
));

UserMessageSegment.displayName = "UserMessageSegment";

/** 分段深度思考：标题点击折叠，流式完自动收起 */
const ThinkingSegment: FC<{
  step: ProcessStepRow;
  autoCollapseOnStreamEnd: boolean;
}> = memo(({ step, autoCollapseOnStreamEnd }) => {
  const thoughtText = asText(step.tool.toolThought);
  const streaming = step.active;

  return (
    <TimelineEntrance
      entryKey={`thinking:${step.id}`}
      active={autoCollapseOnStreamEnd === false}
      className="py-0.5"
    >
      <div data-testid="process-thinking">
        <ThinkingBlock
          text={thoughtText || "…"}
          streaming={streaming}
          autoCollapseOnStreamEnd={autoCollapseOnStreamEnd}
          durationMs={step.durationMs}
          durationLabel={step.durationLabel}
        />
      </div>
    </TimelineEntrance>
  );
});

ThinkingSegment.displayName = "ThinkingSegment";

/** 助手过程回复：深度思考下方常显 */
const AssistantReplySegment: FC<{
  entryKey: string;
  text: string;
  streaming?: boolean;
}> = memo(
  ({ entryKey, text, streaming }) => (
    <TimelineEntrance
      entryKey={entryKey}
      active={Boolean(streaming)}
      className="timeline-assistant-reply mt-0.5 mb-2 w-full max-w-[94%] px-1"
    >
      <div data-testid="process-assistant-reply">
        <MarkdownRenderer
          markDownContent={text}
          isStreaming={Boolean(streaming)}
          className="chat-markdown kimi-md"
        />
      </div>
    </TimelineEntrance>
  )
);

AssistantReplySegment.displayName = "AssistantReplySegment";

function filterConclusionDuplicateSteps(
  steps: ProcessStepRow[],
  conclusionText: string,
  normalizedConclusion: string
): ProcessStepRow[] {
  if (!conclusionText) {
    return steps;
  }
  return steps.filter((step) => {
    if (step.tool.messageType === "task_summary") {
      return !textsNearlyEqual(
        resolveTaskSummaryText(step.tool),
        conclusionText,
        normalizedConclusion
      );
    }
    if (step.kind === "assistant_reply") {
      // 与 resolveSegmentComparableText 同一取文口径，否则 agent_stream 中间轮
      // 会被当成空文本与终答误判重复而整条删掉
      return !textsNearlyEqual(
        resolveAssistantReplyText(step.tool),
        conclusionText,
        normalizedConclusion
      );
    }
    return true;
  });
}

const ProcessSegmentView: FC<{
  segment: ProcessSegment;
  isLast: boolean;
  loading: boolean;
  conclusionText?: string;
  conclusionNormalized?: string;
  ctx: StepRowContext;
}> = memo(({ segment, isLast, loading, conclusionText = "", conclusionNormalized = "", ctx }) => {
  if (segment.type === "thinking") {
    return (
      <ThinkingSegment
        step={segment.step}
        autoCollapseOnStreamEnd={!ctx.runLive}
      />
    );
  }
  if (segment.type === "assistant_reply") {
    return (
      <AssistantReplySegment
        entryKey={`assistant:${segment.step.id}`}
        text={segment.text}
        streaming={loading && isLast && segment.step.active}
      />
    );
  }
  if (segment.type === "user_message") {
    return <UserMessageSegment step={segment.step} />;
  }

  const visibleSteps = filterConclusionDuplicateSteps(
    segment.group.steps,
    conclusionText,
    conclusionNormalized
  );
  if (!visibleSteps.length) {
    return null;
  }
  const visibleGroup = {
    ...segment.group,
    steps: visibleSteps,
    stepCount: visibleSteps.filter((step) => step.kind !== "thinking" && step.kind !== "assistant_reply").length || visibleSteps.length,
    active: visibleSteps.some((step) => step.active),
    completed: visibleSteps.every((step) => step.completed) && !visibleSteps.some((step) => step.active),
  };

  const hasDeepSearch = visibleGroup.steps.some(
    (step) => step.tool.messageType === "deep_search"
  );

  // 单步且已完成：直接展示工具行；进行中/多步走可折叠组。
  // deep_search 查询卡始终展开，避免 URL / 章节总结被折叠吞掉。
  if (visibleGroup.stepCount <= 1 && visibleGroup.completed && !hasDeepSearch) {
    return (
      <div className="w-full">
        {visibleGroup.steps.map((step) => (
          <CompactStepRow key={step.id} step={step} ctx={ctx} />
        ))}
      </div>
    );
  }
  return (
    <StepGroupBlock
      group={visibleGroup}
      defaultOpen={
        visibleGroup.active || !visibleGroup.completed || hasDeepSearch
      }
      forceOpen={visibleGroup.active}
      ctx={ctx}
    />
  );
});

ProcessSegmentView.displayName = "ProcessSegmentView";

/**
 * 产品级 Agent 过程时间线：
 * 深度思考 → 意图句 → 可折叠步骤组（左轨竖线 + 图标 + 耗时）
 * 富交互步骤内联复用 Timeline.ToolItem；工具卡统一走 ToolCallView（registry 分发）。
 */
const AgentStepTimelineComponent: FC<AgentStepTimelineProps> = (props) => {
  const {
    chat,
    isPlanSolveMessage,
    thoughtText = "",
    thoughtStreaming = false,
    thoughtVersionLabel,
    thoughtVersionIndex = 0,
    thoughtVersionTotal = 0,
    onThoughtPrev,
    onThoughtNext,
    canThoughtPrev,
    canThoughtNext,
    planSlot,
    changeActiveChat,
    changePlan,
    changeFile,
    onOpenToolDiff,
    onOpenAgent,
  } = props;

  const nowTick = useNowTicker(Boolean(chat.loading));
  const runLive = isParentLoopLive(chat);
  const processSignature = buildAgentProcessSignature(chat);
  const taskModel = useMemo(
    () =>
      deriveAgentProcessTaskModel({
        chat,
        isPlanSolve: isPlanSolveMessage,
        nowMs: nowTick,
      }),
    // 任务事实没变时（纯正文 / 终答增量）复用上一次模型；
    // chat 只作为派生输入，是否失效由 processSignature 判定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [processSignature, isPlanSolveMessage, nowTick]
  );
  const model = useMemo(
    () =>
      buildAgentProcessViewModel(taskModel, {
        chat: {
          ...chat,
          loading: taskModel.loading,
        },
        thoughtText,
        thoughtStreaming,
        thoughtVersionLabel,
        thoughtVersionIndex,
        thoughtVersionTotal,
        nowMs: nowTick,
      }),
    [
      taskModel,
      chat,
      thoughtText,
      thoughtStreaming,
      thoughtVersionLabel,
      thoughtVersionIndex,
      thoughtVersionTotal,
      nowTick,
    ]
  );

  const ctx = useMemo<StepRowContext>(
    () => ({
      chat,
      runLive,
      changeActiveChat,
      changePlan,
      changeFile,
      onOpenToolDiff,
      onOpenAgent,
    }),
    [
      chat,
      runLive,
      changeActiveChat,
      changePlan,
      changeFile,
      onOpenToolDiff,
      onOpenAgent,
    ]
  );

  if (!model.hasProcess && !planSlot) {
    return null;
  }

  // 终答归一化是全文扫描，只做一次；每个 segment / step 复用同一结果。
  const conclusionText = resolveTaskSummaryText(chat.conclusion);
  const normalizedConclusion = normalizeComparableText(conclusionText);

  return (
    <div
      className="agent-step-timeline w-full max-w-[min(960px,100%)]"
      data-testid="agent-step-timeline"
      aria-label="Agent 执行过程"
    >
      {model.thought ? (
        <TimelineEntrance
          entryKey={`thought:${chat.requestId || chat.startedAt || "run"}`}
          active={runLive}
          className="mb-2"
        >
          <ThinkingBlock
            text={model.thought.text}
            streaming={model.thought.streaming}
            autoCollapseOnStreamEnd={!runLive}
            durationMs={model.thought.durationMs}
            durationLabel={model.thought.durationLabel}
            headerExtra={
              model.thought.versionLabel ? (
                <ThoughtVersionSwitcher
                  versionLabel={model.thought.versionLabel}
                  onPrev={onThoughtPrev}
                  onNext={onThoughtNext}
                  canPrev={canThoughtPrev}
                  canNext={canThoughtNext}
                />
              ) : null
            }
          />
          {!model.thought.streaming && model.intentLine ? (
            <p className="timeline-assistant-reply mt-1 text-ui-caption leading-6">
              {model.intentLine}
            </p>
          ) : null}
        </TimelineEntrance>
      ) : null}

      {planSlot ? <div className="mb-3 w-full">{planSlot}</div> : null}

      {model.segments.length ? (
        <div className="flex flex-col gap-1">
          {model.segments.map((segment, index) => {
            // 终答区已有 conclusion：时间线里不再重复同文案（含误标成思考/过程回复）
            if (
              conclusionText &&
              isDuplicateOfConclusion(segment, conclusionText, normalizedConclusion)
            ) {
              return null;
            }
            const key =
              segment.type === "group"
                ? segment.group.id
                : segment.type === "thinking"
                  ? `think-${segment.step.id}`
                  : segment.type === "assistant_reply"
                    ? `reply-${segment.step.id}`
                    : `msg-${segment.step.id}`;
            return (
              <ProcessSegmentView
                key={key}
                segment={segment}
                isLast={index === model.segments.length - 1}
                loading={chat.loading}
                conclusionText={conclusionText}
                conclusionNormalized={normalizedConclusion}
                ctx={ctx}
              />
            );
          })}
        </div>
      ) : null}
    </div>
  );
};

export const AgentStepTimeline = memo(
  AgentStepTimelineComponent,
  (prev, next) => {
    const processUnchanged =
      buildAgentProcessSignature(prev.chat) ===
      buildAgentProcessSignature(next.chat);
    const conclusionUnchanged =
      resolveTaskSummaryText(prev.chat.conclusion) ===
      resolveTaskSummaryText(next.chat.conclusion);
    return (
      processUnchanged &&
      conclusionUnchanged &&
      isParentLoopLive(prev.chat) === isParentLoopLive(next.chat) &&
      prev.isPlanSolveMessage === next.isPlanSolveMessage &&
      prev.thoughtText === next.thoughtText &&
      prev.thoughtStreaming === next.thoughtStreaming &&
      prev.thoughtVersionLabel === next.thoughtVersionLabel &&
      prev.thoughtVersionIndex === next.thoughtVersionIndex &&
      prev.thoughtVersionTotal === next.thoughtVersionTotal &&
      prev.planSlot === next.planSlot &&
      prev.changeActiveChat === next.changeActiveChat &&
      prev.changePlan === next.changePlan &&
      prev.changeFile === next.changeFile &&
      prev.onOpenToolDiff === next.onOpenToolDiff &&
      prev.onOpenAgent === next.onOpenAgent &&
      prev.onThoughtPrev === next.onThoughtPrev &&
      prev.onThoughtNext === next.onThoughtNext &&
      prev.canThoughtPrev === next.canThoughtPrev &&
      prev.canThoughtNext === next.canThoughtNext
    );
  }
);

AgentStepTimeline.displayName = "AgentStepTimeline";

export default AgentStepTimeline;
