import { buildAction } from "@/utils/chat";
import {
  formatSubAgentDuration,
  isSubAgentParentTask,
  resolveSubAgentDisplay,
} from "@/utils/chat/subagent";
import { isTimelineToolActive } from "@/components/ChatView/streamState";
import { isFileListOnlyTask } from "@/utils/taskArtifacts";
import { hashContent } from "@/lib/hashContent";

function isNil(value: unknown): value is null | undefined {
  return value === null || value === undefined;
}

export type ProcessStepKind =
  | "thinking"
  | "assistant_reply"
  | "read"
  | "edit"
  | "terminal"
  | "search"
  | "browser"
  | "code"
  | "file"
  | "agent"
  | "interactive"
  | "user_message"
  | "tool"
  | "artifact";

export type ProcessStepRow = {
  id: string;
  kind: ProcessStepKind;
  title: string;
  detail?: string;
  expandable: boolean;
  active: boolean;
  completed: boolean;
  durationMs?: number;
  durationLabel?: string;
  tool: CHAT.Task;
  startedAtMs?: number;
};

export type ProcessStepGroup = {
  id: string;
  title: string;
  taskLabel?: string;
  digitalEmployee?: string;
  stepCount: number;
  active: boolean;
  completed: boolean;
  /** 可折叠；user_brief 分隔出的工作段为 true */
  collapsible: boolean;
  durationMs?: number;
  durationLabel?: string;
  steps: ProcessStepRow[];
  /** PlanSolve 容器原始 task，用于折叠标题 */
  container?: CHAT.Task;
};

export type ProcessThoughtBlock = {
  text: string;
  streaming: boolean;
  durationMs?: number;
  durationLabel?: string;
  versionLabel?: string;
  versionIndex: number;
  versionTotal: number;
};

/**
 * 时间线分段（对齐截图）：
 * 深度思考(折叠) → 助手过程回复(常显) → 执行了 N 步(折叠组) → …
 */
export type ProcessSegment =
  | { type: "group"; group: ProcessStepGroup }
  | { type: "thinking"; step: ProcessStepRow }
  | { type: "assistant_reply"; step: ProcessStepRow; text: string }
  | { type: "user_message"; step: ProcessStepRow };

export type AgentProcessModel = {
  hasProcess: boolean;
  loading: boolean;
  thought?: ProcessThoughtBlock;
  /** 短意图句：思考首行，不重复 thought 全文 */
  intentLine?: string;
  /** 有序分段（主渲染源） */
  segments: ProcessSegment[];
  /** 兼容：仅 collapsible 工作组 */
  groups: ProcessStepGroup[];
  totalStepCount: number;
  totalDurationMs?: number;
  totalDurationLabel?: string;
};

/** 任务事实派生的稳定部分；思考/意图句由 {@link buildAgentProcessViewModel} 叠加。 */
export type AgentProcessTaskModel = Omit<
  AgentProcessModel,
  "thought" | "intentLine" | "hasProcess"
> & { hasProcess: boolean };

function asText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function resolveToolName(tool: CHAT.Task): string {
  return asText(
    tool.toolResult?.toolName ||
      (tool.resultMap as Record<string, unknown> | undefined)?.toolName
  ).toLowerCase();
}

/** SendUserMessage / Brief 主可见输出，永不进折叠组，并作为步骤组分界 */
export function isUserBriefTask(tool?: CHAT.Task): boolean {
  if (!tool) {
    return false;
  }
  if (tool.messageType === "user_brief") {
    return true;
  }
  const toolName = resolveToolName(tool);
  return (
    toolName === "sendusermessage" ||
    toolName === "brief" ||
    toolName === "send_user_message"
  );
}

export function resolveUserBriefText(tool: CHAT.Task): string {
  const resultMap = (tool.resultMap || {}) as Record<string, unknown>;
  const nested = (resultMap.resultMap || {}) as Record<string, unknown>;
  const toolAny = tool as unknown as Record<string, unknown>;
  return asText(
    nested.message || resultMap.message || toolAny.message || tool.toolThought
  );
}

/** 仅原生 CoT / plan 思考 → 深度思考（tool_thought 永不当思考） */
function isNativeReasoningTask(tool: CHAT.Task): boolean {
  return (
    tool.messageType === "llm_reasoning" || tool.messageType === "plan_thought"
  );
}

/**
 * 助手过程回复：有 tool 时的 content（tool_thought）与正文流行模型的中间轮（agent_stream）。
 * 常显切分点；不是思考 —— 它必须夹在两组工具卡片之间，而不是堆在对话最下方。
 */
function isAssistantProcessReplyTask(tool: CHAT.Task): boolean {
  return tool.messageType === "tool_thought" || tool.messageType === "agent_stream";
}

/** 是否计入「执行了 N 个步骤」：思考/过程回复/user_brief 不计入 */
function countsAsExecutableStep(tool: CHAT.Task): boolean {
  if (
    isUserBriefTask(tool) ||
    isNativeReasoningTask(tool) ||
    isAssistantProcessReplyTask(tool)
  ) {
    return false;
  }
  return true;
}

/**
 * 助手过程回复正文。tool_thought 读 toolThought；agent_stream 读 result。
 * 行模型下同一时刻只会有一种形态，取到即可。
 */
export function resolveAssistantReplyText(tool: CHAT.Task): string {
  return asText(tool.toolThought || tool.result);
}

export function parseMessageTimeMs(value?: unknown): number | undefined {
  if (isNil(value) || value === "") {
    return undefined;
  }

  if (typeof value === "number") {
    if (!Number.isFinite(value) || value < 0) {
      return undefined;
    }
    return value < 1e12 ? value * 1000 : value;
  }

  if (typeof value !== "string") {
    return undefined;
  }

  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric >= 0) {
    // 兼容秒级时间戳
    return numeric < 1e12 ? numeric * 1000 : numeric;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

export function formatProcessDuration(ms?: number): string {
  if (isNil(ms) || !Number.isFinite(ms) || ms < 0) {
    return "";
  }
  if (ms < 100) {
    return "0.1s";
  }
  if (ms < 1000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  if (ms < 60_000) {
    const seconds = ms / 1000;
    return Number.isInteger(seconds) || seconds >= 10
      ? `${Math.round(seconds)}s`
      : `${seconds.toFixed(1)}s`;
  }
  return formatSubAgentDuration(ms) || `${Math.round(ms / 1000)}s`;
}

function isStepCompleted(tool: CHAT.Task): boolean {
  return Boolean(tool.finish || tool.isFinal || tool.resultMap?.isFinal);
}

function resolveStepId(tool: CHAT.Task, index: number): string {
  return (
    tool.id ||
    tool.messageId ||
    resolveToolCallId(tool) ||
    `${tool.messageType || "step"}-${tool.taskId || index}`
  );
}

function resolveToolCallId(tool: CHAT.Task): string {
  const resultMap = (tool.resultMap || {}) as Record<string, unknown>;
  const nested = (resultMap.resultMap || {}) as Record<string, unknown>;
  return asText(
    resultMap.toolCallId ||
      nested.toolCallId ||
      tool.toolResult?.toolName
  );
}

/**
 * 消息类型 → 步骤类型：只在 `isUserBriefTask` / `isSubAgentParentTask` **之前**
 * 生效的那些常量映射。
 *
 * 顺序是刻意的：master 的判断链是
 *   thinking / assistant_reply → user_brief → 子 Agent → 其余常量 → 工具名关键字
 * 子 Agent 判定看的是 toolName / input（与 messageType 无关），所以它必须先于
 * 其余常量表 —— 否则 messageType 为 deep_search / code / file … 的 Agent 派发卡
 * 会被表抢先命中，降级成 search / code / artifact，子 Agent 的进度行、时长、
 * liveText 和 Dock 详情全部不渲染。
 */
export const STEP_KIND_BEFORE_SUBAGENT: Record<string, ProcessStepKind> = {
  llm_reasoning: "thinking",
  plan_thought: "thinking",
  // agent_stream 是 react 主 agent 的正文流行模型：中间轮（moreRounds=true）
  // 是助手过程回复；终轮由结论区承接（handleAgentStreamMessage 只会把中间轮
  // 封口进时间线），这里统一按 assistant_reply 渲染，保证它插在工具卡片之间
  // 而不是沉到对话底部。
  tool_thought: "assistant_reply",
  agent_stream: "assistant_reply",
};

/** 消息类型 → 步骤类型：子 Agent 判定之后的常量映射。 */
export const STEP_KIND_AFTER_SUBAGENT: Record<string, ProcessStepKind> = {
  ask_user_question: "interactive",
  desktop_control: "interactive",
  plan_approval: "interactive",
  session_tasks: "interactive",
  browser: "browser",
  deep_search: "search",
  knowledge: "search",
  code: "code",
  html: "code",
  file: "file",
  markdown: "artifact",
  ppt: "artifact",
  data_analysis: "artifact",
};

/**
 * 工具名关键字 → 步骤类型。按顺序匹配，命中即返回。
 *
 * 工具名没有统一命名规范（同一语义在不同模型下叫法不同），只能靠关键字兜底。
 *
 * 拍平成单个有序数组而不是「关键字组」的嵌套表：每个 timeline task 都要过一遍
 * 这里，嵌套表要为每组新建一个 `.some()` 闭包，长会话下是白费的 GC 压力。
 */
const STEP_KIND_BY_TOOL_NAME_KEYWORD: ReadonlyArray<
  readonly [keyword: string, kind: ProcessStepKind]
> = [
  ["read", "read"],
  ["cat", "read"],
  ["view", "read"],
  ["open", "read"],
  ["edit", "edit"],
  ["write", "edit"],
  ["create", "edit"],
  ["patch", "edit"],
  ["replace", "edit"],
  ["bash", "terminal"],
  ["shell", "terminal"],
  ["terminal", "terminal"],
  ["exec", "terminal"],
  ["run", "terminal"],
  ["search", "search"],
  ["grep", "search"],
  ["web", "search"],
  ["crawl", "search"],
];

export function resolveProcessStepKind(tool: CHAT.Task): ProcessStepKind {
  const messageType = tool.messageType || "";

  // 两张表都用 Object.hasOwn 查：messageType 是后端传来的字符串，
  // 直接下标会让 "toString" / "constructor" 这类键命中原型方法。

  // 子 Agent 判定之前的常量映射
  if (Object.hasOwn(STEP_KIND_BEFORE_SUBAGENT, messageType)) {
    return STEP_KIND_BEFORE_SUBAGENT[messageType];
  }

  if (isUserBriefTask(tool)) {
    return "user_message";
  }

  if (isSubAgentParentTask(tool)) {
    return "agent";
  }

  // 子 Agent 判定之后的常量映射
  if (Object.hasOwn(STEP_KIND_AFTER_SUBAGENT, messageType)) {
    return STEP_KIND_AFTER_SUBAGENT[messageType];
  }

  const toolName = resolveToolName(tool);
  for (const [keyword, kind] of STEP_KIND_BY_TOOL_NAME_KEYWORD) {
    if (toolName.includes(keyword)) {
      return kind;
    }
  }

  return "tool";
}

function resolveStepTitle(tool: CHAT.Task, kind: ProcessStepKind): {
  title: string;
  detail?: string;
} {
  if (kind === "thinking") {
    return { title: "深度思考" };
  }

  if (kind === "assistant_reply") {
    const text = resolveAssistantReplyText(tool);
    return {
      title: "助手回复",
      detail: text ? (text.length > 48 ? `${text.slice(0, 45)}…` : text) : undefined,
    };
  }

  if (kind === "user_message") {
    const text = resolveUserBriefText(tool);
    return {
      title: "给用户的消息",
      detail: text ? (text.length > 48 ? `${text.slice(0, 45)}…` : text) : undefined,
    };
  }

  if (isSubAgentParentTask(tool)) {
    const sub = resolveSubAgentDisplay(tool);
    return {
      title: sub.status === "running" ? "派发子智能体" : "子智能体",
      detail: sub.description || sub.subagentType,
    };
  }

  const action = buildAction(tool);
  const title = asText(action.action) || "调用工具";
  const detail = asText(action.name) || asText(action.tool) || undefined;

  // Cursor 风格：动词 + 目标更短
  if (kind === "read" && detail) {
    return {
      title: `读取 ${detail}`,
      detail: undefined
    };
  }
  if (kind === "edit" && detail) {
    return {
      title: `编辑 ${detail}`,
      detail: undefined
    };
  }
  if (kind === "file" && detail) {
    const command = asText(tool.resultMap?.command);
    if (command) {
      return {
        title: `${command} ${detail}`,
        detail: undefined
      };
    }
    return {
      title: detail,
      detail: undefined
    };
  }

  return {
    title,
    detail
  };
}

type TimeInterval = {
  startedAtMs: number;
  finishedAtMs: number;
};

type TimingRecord = Record<string, unknown>;

function asRecord(value: unknown): TimingRecord | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as TimingRecord)
    : undefined;
}

function resolveDurationMs(value: unknown): number | undefined {
  if (isNil(value) || (typeof value === "string" && !value.trim())) {
    return undefined;
  }
  if (typeof value === "number") {
    return Number.isFinite(value) && value >= 0 ? value : undefined;
  }
  if (typeof value !== "string") {
    return undefined;
  }
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? numeric : undefined;
}

function firstDurationMs(...values: unknown[]): number | undefined {
  for (const value of values) {
    const durationMs = resolveDurationMs(value);
    if (!isNil(durationMs)) {
      return durationMs;
    }
  }
  return undefined;
}

function firstTimestampMs(
  source: TimingRecord | undefined,
  ...keys: string[]
): number | undefined {
  if (!source) {
    return undefined;
  }
  for (const key of keys) {
    if (!(key in source)) {
      continue;
    }
    const timestampMs = parseMessageTimeMs(source[key]);
    if (!isNil(timestampMs)) {
      return timestampMs;
    }
  }
  return undefined;
}

function resolveTaskRecordParts(tool: CHAT.Task): {
  task: TimingRecord;
  timing?: TimingRecord;
  resultMap?: TimingRecord;
  nestedResultMap?: TimingRecord;
} {
  const task = tool as unknown as TimingRecord;
  const timing = asRecord(task.timing);
  const resultMap = asRecord(task.resultMap);
  const nestedResultMap = asRecord(resultMap?.resultMap);
  return { task, timing, resultMap, nestedResultMap };
}

function resolveTimingDurationMs(tool: CHAT.Task): number | undefined {
  const { timing } = resolveTaskRecordParts(tool);
  return firstDurationMs(timing?.durationMs);
}

/**
 * 事件 resultMap 在实时路径会被展开到 task 顶层，在历史路径通常仍嵌在
 * resultMap 中；两种形态都属于同一层级，必须保持相同优先级。
 */
function resolveResultMapDurationMs(tool: CHAT.Task): number | undefined {
  const { task, resultMap, nestedResultMap } = resolveTaskRecordParts(tool);
  return firstDurationMs(
    resultMap?.durationMs,
    resultMap?.duration_ms,
    nestedResultMap?.durationMs,
    nestedResultMap?.duration_ms,
    task.durationMs,
    task.duration_ms
  );
}

function resolveLegacyDurationMs(tool: CHAT.Task): number | undefined {
  const { task, resultMap, nestedResultMap } = resolveTaskRecordParts(tool);
  return firstDurationMs(
    task.elapsedMs,
    task.subAgentElapsedMs,
    resultMap?.elapsedMs,
    resultMap?.subAgentElapsedMs,
    nestedResultMap?.elapsedMs,
    nestedResultMap?.subAgentElapsedMs
  );
}

function resolveSubAgentDurationMs(tool: CHAT.Task): number | undefined {
  if (!isSubAgentParentTask(tool)) {
    return undefined;
  }
  return resolveDurationMs(resolveSubAgentDisplay(tool).totalDurationMs);
}

function durationFromInterval(interval?: TimeInterval): number | undefined {
  if (!interval) {
    return undefined;
  }
  return Math.max(interval.finishedAtMs - interval.startedAtMs, 0);
}

function resolveExplicitInterval(tool: CHAT.Task): TimeInterval | undefined {
  const { task, timing, resultMap, nestedResultMap } = resolveTaskRecordParts(tool);
  const sources = [timing, task, resultMap, nestedResultMap];

  for (const source of sources) {
    if (!source) {
      continue;
    }
    const startedAtMs = firstTimestampMs(source, "startedAt", "started_at");
    const finishedAtMs = firstTimestampMs(source, "finishedAt", "finished_at");

    if (!isNil(startedAtMs) && !isNil(finishedAtMs) && finishedAtMs >= startedAtMs) {
      return { startedAtMs, finishedAtMs };
    }
  }

  return undefined;
}

function resolveTaskStartedAtMs(tool: CHAT.Task): number | undefined {
  const explicitInterval = resolveExplicitInterval(tool);
  if (explicitInterval) {
    return explicitInterval.startedAtMs;
  }

  const { task, timing, resultMap, nestedResultMap } = resolveTaskRecordParts(tool);
  return (
    firstTimestampMs(timing, "startedAt", "started_at") ??
    firstTimestampMs(task, "startedAt", "started_at") ??
    firstTimestampMs(resultMap, "startedAt", "started_at") ??
    firstTimestampMs(nestedResultMap, "startedAt", "started_at") ??
    firstTimestampMs(task, "messageTime") ??
    firstTimestampMs(resultMap, "messageTime") ??
    firstTimestampMs(nestedResultMap, "messageTime")
  );
}

function resolveTaskInterval(
  tool: CHAT.Task,
  options: { loading: boolean; nowMs: number }
): TimeInterval | undefined {
  const explicitInterval = resolveExplicitInterval(tool);
  if (explicitInterval) {
    return explicitInterval;
  }

  const startedAtMs = resolveTaskStartedAtMs(tool);
  if (
    options.loading &&
    !isNil(startedAtMs) &&
    (isTimelineToolActive(tool) || !isStepCompleted(tool))
  ) {
    return {
      startedAtMs,
      finishedAtMs: Math.max(options.nowMs, startedAtMs),
    };
  }

  return undefined;
}

function resolveSingleToolDuration(
  step: { tool: CHAT.Task; startedAtMs?: number },
  nextStartedAtMs: number | undefined,
  options: { loading: boolean; nowMs: number }
): number | undefined {
  // 新回放 timing 是最可信的单 invocation 事实。
  const timingDurationMs = resolveTimingDurationMs(step.tool);
  if (!isNil(timingDurationMs)) {
    return timingDurationMs;
  }

  const resultMapDurationMs = resolveResultMapDurationMs(step.tool);
  if (!isNil(resultMapDurationMs)) {
    return resultMapDurationMs;
  }

  const subAgentDurationMs = resolveSubAgentDurationMs(step.tool);
  if (!isNil(subAgentDurationMs)) {
    return subAgentDurationMs;
  }

  const explicitIntervalDurationMs = durationFromInterval(
    resolveExplicitInterval(step.tool)
  );
  if (!isNil(explicitIntervalDurationMs)) {
    return explicitIntervalDurationMs;
  }

  const legacyDurationMs = resolveLegacyDurationMs(step.tool);
  if (!isNil(legacyDurationMs)) {
    return legacyDurationMs;
  }

  // 旧实时事件只有 messageTime 时，只能用下一个明确事件时间作为保守边界。
  // 整轮 finishedAt 不在这里使用，避免把 run 尾部伪装成工具耗时。
  if (
    !isNil(step.startedAtMs) &&
    !isNil(nextStartedAtMs) &&
    nextStartedAtMs >= step.startedAtMs
  ) {
    return nextStartedAtMs - step.startedAtMs;
  }

  if (
    options.loading &&
    !isNil(step.startedAtMs) &&
    (isTimelineToolActive(step.tool) || !isStepCompleted(step.tool))
  ) {
    return Math.max(options.nowMs - step.startedAtMs, 0);
  }

  return undefined;
}

function mergeIntervals(intervals: TimeInterval[]): number | undefined {
  if (!intervals.length) {
    return undefined;
  }

  const sorted = [...intervals].sort(
    (left, right) => left.startedAtMs - right.startedAtMs
  );
  let totalMs = 0;
  let current = sorted[0];

  for (const interval of sorted.slice(1)) {
    if (interval.startedAtMs <= current.finishedAtMs) {
      current = {
        startedAtMs: current.startedAtMs,
        finishedAtMs: Math.max(current.finishedAtMs, interval.finishedAtMs),
      };
      continue;
    }
    totalMs += Math.max(current.finishedAtMs - current.startedAtMs, 0);
    current = interval;
  }

  return totalMs + Math.max(current.finishedAtMs - current.startedAtMs, 0);
}

function resolveGroupDurationMs(
  steps: ProcessStepRow[],
  options: { loading: boolean; nowMs: number }
): number | undefined {
  const intervals = steps
    .map((step) => resolveTaskInterval(step.tool, options))
    .filter((interval): interval is TimeInterval => !isNil(interval));
  return mergeIntervals(intervals);
}

function estimateDurations(
  steps: Array<{ tool: CHAT.Task; startedAtMs?: number }>,
  options: { loading: boolean; nowMs: number }
): Array<number | undefined> {
  // 反向单遍记录「下一个有 startedAt 的步骤」：避免每个 step 都 slice + find 造成 O(n²)。
  const nextStartedAt: Array<number | undefined> = new Array(steps.length);
  let next: number | undefined;
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    nextStartedAt[index] = next;
    const startedAtMs = steps[index]?.startedAtMs;
    if (!isNil(startedAtMs)) {
      next = startedAtMs;
    }
  }
  return steps.map((step, index) =>
    resolveSingleToolDuration(step, nextStartedAt[index], options)
  );
}

function flattenGroupChildren(container: CHAT.Task): CHAT.Task[] {
  return Array.isArray(container.children)
    ? container.children.filter((task) => !isFileListOnlyTask(task))
    : [];
}

function buildStepRows(
  tools: CHAT.Task[],
  options: {
    loading: boolean;
    nowMs: number;
  }
): ProcessStepRow[] {
  const prepared = tools.map((tool, index) => ({
    tool,
    startedAtMs: resolveTaskStartedAtMs(tool),
    index,
  }));
  const durations = estimateDurations(prepared, options);

  return prepared.map((item, index) => {
    const kind = resolveProcessStepKind(item.tool);
    const { title, detail } = resolveStepTitle(item.tool, kind);
    // 整轮结束后禁止残留 active，否则深度思考/步骤会一直 shimmer
    const active = options.loading && isTimelineToolActive(item.tool);
    const completed =
      !active && (isStepCompleted(item.tool) || !options.loading);
    const durationMs = durations[index];
    const expandable =
      kind === "thinking" ||
      kind === "assistant_reply" ||
      kind === "interactive" ||
      kind === "user_message" ||
      kind === "agent" ||
      kind === "artifact" ||
      item.tool.messageType === "deep_search" ||
      item.tool.messageType === "browser" ||
      Boolean(item.tool.children?.length);

    return {
      id: resolveStepId(item.tool, index),
      kind,
      title,
      detail,
      expandable,
      active,
      completed,
      durationMs,
      durationLabel: formatProcessDuration(durationMs),
      tool: item.tool,
      startedAtMs: item.startedAtMs,
    };
  });
}

function resolveDigitalEmployee(container: CHAT.Task): string | undefined {
  return container.children?.find((child) => child.digitalEmployee)?.digitalEmployee;
}

function buildGroupTitle(
  container: CHAT.Task | undefined,
  stepCount: number,
  isPlanSolve: boolean,
  active: boolean
): string {
  if (isPlanSolve && container?.task?.trim()) {
    return container.task.trim();
  }
  if (active) {
    return stepCount > 0 ? `正在执行 · ${stepCount} 个步骤` : "正在执行";
  }
  return `执行了 ${stepCount} 个步骤`;
}

/**
 * 分段规则（对齐产品截图）：
 * - 非空 assistant_reply → 先吐出缓冲中的「深度思考」为外层块，再冲刷工具组，再常显回复（切开）
 * - 空 assistant_reply → 不切开、不展示
 * - thinking 无后续非空回复时 → 融入「执行了 N 步」组内作为一步
 * - user_brief → 常显并切开
 */
export function segmentProcessSteps(
  steps: ProcessStepRow[],
  options: {
    loading: boolean;
    isPlanSolve: boolean;
    container?: CHAT.Task;
    groupIndexBase?: number;
    nowMs?: number;
  }
): ProcessSegment[] {
  // 这里把后端任务事件转换成 UI 语义：思考和工具先进入缓冲区，助手过程回复、
  // user_brief 会切开缓冲区，真实工具才形成可折叠步骤组。切分规则必须与
  // Timeline 的展示语义一致，否则同一批事件会在实时流和历史回放中呈现不同结构。
  const segments: ProcessSegment[] = [];
  /** 缓冲：thinking + tools；有非空助手回复时 thinking 提出外层，否则 thinking 进组 */
  let buffer: ProcessStepRow[] = [];
  let groupSerial = options.groupIndexBase ?? 0;

  const isThinkingStep = (step: ProcessStepRow) =>
    step.kind === "thinking" || isNativeReasoningTask(step.tool);

  const emitStandaloneThinking = (items: ProcessStepRow[]) => {
    for (const step of items) {
      if (isThinkingStep(step)) {
        segments.push({ type: "thinking", step });
      }
    }
  };

  const flushWorkGroup = (forceActive?: boolean) => {
    if (!buffer.length) {
      return;
    }
    const executables = buffer.filter((step) => countsAsExecutableStep(step.tool));
    const thinkings = buffer.filter((step) => isThinkingStep(step));

    // 无真实工具：仅深度思考 → 外层折叠块（不当空组）
    if (!executables.length) {
      emitStandaloneThinking(thinkings);
      buffer = [];
      return;
    }

    // 有工具：thinking 与 tools 按原序进组（助手为空时深度思考是步骤之一）
    const ordered = buffer.filter(
      (step) => countsAsExecutableStep(step.tool) || isThinkingStep(step)
    );
    const displayCount = executables.length;
    // 仅按组内步骤是否仍在执行判定 active，避免整轮 loading 期间步骤组无法折叠
    const active =
      Boolean(forceActive) || ordered.some((step) => step.active);
    const completed = ordered.every((step) => step.completed) && !active;
    const durationMs = resolveGroupDurationMs(ordered, {
      loading: options.loading,
      nowMs: options.nowMs ?? Date.now(),
    });
    groupSerial += 1;
    // 流式追加容器时，容器本身可能持续变化，不能作为 React 身份，否则整组会
    // 卸载重挂并重播入场动画。组内第一个真实步骤只会追加，身份更稳定；
    // PlanSolve 也只借用 container 生成标题，不把它当作组 key。
    const firstExecutable = executables[0] || ordered[0];
    // Deep Research 会从一张“空查询占位卡”升级为首个查询卡，子卡 id 会变，
    // 但它始终属于同一个 tool messageId，所以优先使用消息身份。
    const groupIdentity =
      firstExecutable?.tool.messageId ||
      firstExecutable?.tool.id ||
      options.container?.id ||
      options.container?.messageId ||
      "work-group";
    segments.push({
      type: "group",
      group: {
        id: `group:${groupIdentity}:${groupSerial}`,
        title: buildGroupTitle(
          options.container,
          displayCount,
          options.isPlanSolve,
          active
        ),
        taskLabel: options.container?.task,
        digitalEmployee: options.container
          ? resolveDigitalEmployee(options.container)
          : undefined,
        stepCount: displayCount,
        active,
        completed,
        collapsible: true,
        durationMs,
        durationLabel: formatProcessDuration(durationMs),
        steps: ordered,
        container: options.container,
      },
    });
    buffer = [];
  };

  /**
   * 非空助手回复前的切开：
   * 1) 缓冲里先出现的 thinking 提出为外层「深度思考」
   * 2) 剩余 tools（及夹在中间的 thinking）冲成步骤组
   */
  const flushBeforeAssistantReply = () => {
    if (!buffer.length) {
      return;
    }
    // 前缀连续 thinking → 外层
    const prefixThinking: ProcessStepRow[] = [];
    let i = 0;
    while (i < buffer.length && isThinkingStep(buffer[i])) {
      prefixThinking.push(buffer[i]);
      i += 1;
    }
    const rest = buffer.slice(i);
    buffer = [];
    emitStandaloneThinking(prefixThinking);
    buffer = rest;
    flushWorkGroup(false);
  };

  for (const step of steps) {
    // 1) 深度思考：先进缓冲（是否外提取决于后续有无非空助手回复）
    if (isThinkingStep(step)) {
      buffer.push(step);
      continue;
    }

    // 2) 助手过程回复
    if (step.kind === "assistant_reply" || isAssistantProcessReplyTask(step.tool)) {
      const text = resolveAssistantReplyText(step.tool);
      // 空回复：不切开、不展示
      if (!text) {
        continue;
      }
      flushBeforeAssistantReply();
      segments.push({ type: "assistant_reply", step, text });
      continue;
    }

    // 3) user_brief：切开并独立展示
    if (step.kind === "user_message" || isUserBriefTask(step.tool)) {
      flushWorkGroup(false);
      segments.push({ type: "user_message", step });
      continue;
    }

    // 3.5) 富交互步骤（AskUserQuestion / ExitPlanMode / 桌面控制）：**先切开前面的工具组**。
    // 它本身不是一个「可折叠步骤」，若与普通工具一起进 buffer，前面那个写入工具就会被
    // 并进同一组，在视觉上被提问卡吞掉（用户看到的就是「AskUser 前面的 Write 不见了」）。
    // 切开后它单独成组，组内只有它（必要时带相邻思考）。
    if (step.kind === "interactive") {
      flushWorkGroup(false);
      buffer.push(step);
      continue;
    }

    // 4) 真实工具
    buffer.push(step);
  }

  // 末组是否 active 由组内步骤状态决定，不因整轮 loading 强制保持展开
  flushWorkGroup(false);

  // 终答只走 chat.conclusion / result，不再把过程文提升为独立终答段（避免与结论重复）
  return segments;
}

function extractIntentLine(thoughtText?: string): string | undefined {
  const thought = asText(thoughtText);
  if (!thought) {
    return undefined;
  }
  const firstLine = thought
    .split(/\n/)
    .map((line) => line.trim())
    .find((line) => line.length > 0);
  if (!firstLine) {
    return undefined;
  }
  // 过长时截断为意图句
  if (firstLine.length <= 80) {
    return firstLine;
  }
  return `${firstLine.slice(0, 77)}…`;
}

function resolveRunDurationMs(
  chat: CHAT.ChatItem,
  startedAtMs: number | undefined,
  nowMs: number
): number | undefined {
  const recordedDurationMs = resolveDurationMs(chat.runDurationMs);
  if (!isNil(recordedDurationMs)) {
    return recordedDurationMs;
  }

  const runFinishedAtMs = parseMessageTimeMs(chat.finishedAt);
  if (
    !isNil(startedAtMs) &&
    !isNil(runFinishedAtMs) &&
    runFinishedAtMs >= startedAtMs
  ) {
    return runFinishedAtMs - startedAtMs;
  }

  if (chat.loading && !isNil(startedAtMs)) {
    return Math.max(nowMs - startedAtMs, 0);
  }

  return undefined;
}

export type DeriveAgentProcessModelInput = {
  chat: CHAT.ChatItem;
  isPlanSolve: boolean;
  thoughtText?: string;
  thoughtStreaming?: boolean;
  thoughtVersionLabel?: string;
  thoughtVersionIndex?: number;
  thoughtVersionTotal?: number;
  nowMs?: number;
};

/**
 * 任务事实签名：仅当其中字段变化时才需要重建过程模型。
 *
 * `chat` 引用在流式期间每帧都变（正文、终答、运行状态），但任务树往往没变；
 * 这里把 `deriveAgentProcessTaskModel` 会读取的字段压成字符串，供上层 `useMemo`
 * 判断是否可以复用上一次派生结果。纯正文增量不会改变签名。
 */
export function buildAgentProcessSignature(chat: CHAT.ChatItem): string {
  const signatureText = (value: unknown) => {
    if (typeof value !== "string" || !value) {
      return "";
    }
    return `${value.length}:${hashContent(value)}`;
  };
  /**
   * 文件签名：步骤标题会用 `buildAction` 取主文件（如图片生成），
   * 文件列表变化时任务模型必须失效。
   */
  const signatureFiles = (files: unknown) => {
    if (!Array.isArray(files) || !files.length) {
      return "";
    }
    return files
      .map((value) => {
        const file = (value || {}) as Record<string, unknown>;
        return [
          file.displayName,
          file.fileName,
          file.name,
          file.resourceKey,
          file.previewUrl,
          file.downloadUrl,
          file.missing ? 1 : 0,
        ]
          .filter(Boolean)
          .join("@");
      })
      .join(",");
  };
  const parts: string[] = [
    chat.loading ? "loading" : "idle",
    String(chat.startedAt || ""),
    String(chat.finishedAt || ""),
    String(chat.runDurationMs ?? ""),
  ];
  const walk = (task: CHAT.Task) => {
    const resultMap = (task.resultMap || {}) as Record<string, unknown>;
    const nested = (resultMap.resultMap || {}) as Record<string, unknown>;
    const timing = (task as { timing?: Record<string, unknown> }).timing || {};
    const searchResult = (resultMap.searchResult || {}) as Record<string, unknown>;
    const includeTaskMessageTime = task.messageType !== "deep_search";
    parts.push(
      [
        task.id || "",
        task.messageId || "",
        task.messageType || "",
        includeTaskMessageTime ? task.messageTime || "" : "",
        includeTaskMessageTime ? resultMap.messageTime || "" : "",
        includeTaskMessageTime ? nested.messageTime || "" : "",
        task.isFinal ? 1 : 0,
        task.finish ? 1 : 0,
        task.task?.trim() || "",
        task.digitalEmployee || "",
        task.toolResult?.toolName || "",
        String((task.toolResult as { toolCallId?: unknown } | undefined)?.toolCallId || ""),
        String((task as { toolCallId?: unknown }).toolCallId || ""),
        signatureText(task.toolThought),
        signatureText(resultMap.message),
        signatureText(resultMap.command),
        signatureText(resultMap.primaryFileName),
        signatureText(resultMap.input ? JSON.stringify(resultMap.input) : ""),
        signatureText(
          resultMap.toolParam ? JSON.stringify(resultMap.toolParam) : ""
        ),
        signatureText(nested.input ? JSON.stringify(nested.input) : ""),
        signatureText(
          nested.toolParam ? JSON.stringify(nested.toolParam) : ""
        ),
        signatureText(task.result),
        signatureText(task.planThought),
        task.moreRounds ? 1 : 0,
        signatureFiles(task.fileList),
        signatureFiles(resultMap.fileList),
        signatureFiles(task.artifactRefs),
        signatureFiles(resultMap.artifactRefs),
        resultMap.childSessionId || "",
        resultMap.childRequestId || "",
        resultMap.messageType || "",
        resultMap.status || "",
        resultMap.toolName || "",
        resultMap.toolCallId || "",
        resultMap.isFinal ? 1 : 0,
        signatureText(resultMap.answer),
        signatureText(resultMap.result),
        signatureText(resultMap.argumentsText),
        signatureText(resultMap.subAgentLiveText),
        String(resultMap.subAgentElapsedMs ?? ""),
        Array.isArray(resultMap.subAgentProgressLines)
          ? resultMap.subAgentProgressLines.length
          : "",
        Array.isArray(searchResult.query) ? searchResult.query.join(",") : "",
        Array.isArray(searchResult.docs) ? searchResult.docs.length : "",
        String(timing.startedAt ?? timing.started_at ?? ""),
        String(timing.finishedAt ?? timing.finished_at ?? ""),
        String(timing.durationMs ?? timing.duration_ms ?? ""),
        String(resultMap.startedAt ?? resultMap.started_at ?? ""),
        String(resultMap.finishedAt ?? resultMap.finished_at ?? ""),
        String(resultMap.durationMs ?? resultMap.duration_ms ?? ""),
        String(nested.messageType || ""),
        String(nested.childSessionId || ""),
        String(nested.childRequestId || ""),
        String(nested.status || ""),
        String(nested.toolCallId || ""),
        String(nested.isFinal ? 1 : 0),
        String(nested.startedAt ?? nested.started_at ?? ""),
        String(nested.finishedAt ?? nested.finished_at ?? ""),
        String(nested.durationMs ?? nested.duration_ms ?? ""),
        String(
          (task as { durationMs?: unknown; duration_ms?: unknown }).durationMs ??
            (task as { duration_ms?: unknown }).duration_ms ??
            ""
        ),
        task.plan ? (task.plan.stepStatus || []).join(",") : "",
        task.plan ? signatureText(JSON.stringify(task.plan)) : "",
      ].join(":")
    );
    for (const child of task.children || []) {
      walk(child as CHAT.Task);
    }
  };
  for (const group of chat.tasks || []) {
    for (const task of group || []) {
      walk(task);
    }
  }
  return parts.join("|");
}

/** 只从任务事实派生模型；思考文本与意图句由 {@link buildAgentProcessViewModel} 叠加。 */
export function deriveAgentProcessTaskModel(input: {
  chat: CHAT.ChatItem;
  isPlanSolve: boolean;
  nowMs?: number;
}): AgentProcessTaskModel {
  const { chat, isPlanSolve, nowMs = Date.now() } = input;

  const loading = Boolean(chat.loading);
  const startedAtMs = parseMessageTimeMs(chat.startedAt);

  // PlanSolve 的 task 容器承载计划步骤，必须保留容器边界；普通 ReAct 历史数据
  // 可能被按“一个工具一个容器”保存，先合并 children 再分组，才能恢复连续执行组。
  // 这是展示层的重建，不会改写原始 chat.tasks，也不改变 ledger 的事件顺序。
  const segments: ProcessSegment[] = [];
  let groupSerial = 0;
  const taskGroups = chat.tasks || [];

  // ReAct / 普通任务：跨 container 合并 children 再切分，避免历史回放「一工具一组」时
  // 永远落成 stepCount=1 的已完成组而被时间线压成平铺（折叠「执行了 N 步」消失）。
  // PlanSolve 仍按 plan task 容器边界分段，保留步骤标题。
  type ContainerSlice = {
    children: CHAT.Task[];
    container?: CHAT.Task;
    groupIndex: number;
    containerIndex: number;
    isLastContainer: boolean;
  };
  const slices: ContainerSlice[] = [];

  if (!isPlanSolve) {
    const merged: CHAT.Task[] = [];
    let lastMeta: Omit<ContainerSlice, "children"> | undefined;
    taskGroups.forEach((group, groupIndex) => {
      (group || []).forEach((container, containerIndex) => {
        const children = flattenGroupChildren(container);
        if (!children.length) {
          return;
        }
        merged.push(...children);
        lastMeta = {
          container,
          groupIndex,
          containerIndex,
          isLastContainer:
            groupIndex === taskGroups.length - 1 &&
            containerIndex === (group || []).length - 1,
        };
      });
    });
    if (merged.length && lastMeta) {
      slices.push({ children: merged, ...lastMeta });
    }
  } else {
    taskGroups.forEach((group, groupIndex) => {
      (group || []).forEach((container, containerIndex) => {
        const children = flattenGroupChildren(container);
        if (!children.length) {
          return;
        }
        slices.push({
          children,
          container,
          groupIndex,
          containerIndex,
          isLastContainer:
            groupIndex === taskGroups.length - 1 &&
            containerIndex === (group || []).length - 1,
        });
      });
    });
  }

  for (const slice of slices) {
    const steps = buildStepRows(slice.children, {
      loading,
      nowMs,
    });
    if (!steps.length) {
      continue;
    }

    const sliced = segmentProcessSteps(steps, {
      loading: loading && slice.isLastContainer,
      isPlanSolve,
      container: slice.container,
      groupIndexBase: groupSerial,
      nowMs,
    });
    for (const segment of sliced) {
      if (segment.type === "group") {
        groupSerial += 1;
      }
      segments.push(segment);
    }
  }

  const groups = segments
    .filter((segment): segment is Extract<ProcessSegment, { type: "group" }> =>
      segment.type === "group"
    )
    .map((segment) => segment.group);

  const totalStepCount = groups.reduce((sum, group) => sum + group.stepCount, 0);
  const totalDurationMs = resolveRunDurationMs(chat, startedAtMs, nowMs);

  return {
    hasProcess: segments.length > 0,
    loading,
    segments,
    groups,
    totalStepCount,
    totalDurationMs,
    totalDurationLabel: formatProcessDuration(totalDurationMs),
  };
}

/**
 * 把思考块与意图句叠到任务模型上。
 *
 * 拆成独立步骤，是为了让流式思考文本每帧变化时只重跑这一层，
 * 不必重新遍历任务树。
 */
export function buildAgentProcessViewModel(
  base: AgentProcessTaskModel,
  input: {
    chat: CHAT.ChatItem;
    thoughtText?: string;
    thoughtStreaming?: boolean;
    thoughtVersionLabel?: string;
    thoughtVersionIndex?: number;
    thoughtVersionTotal?: number;
    nowMs?: number;
  }
): AgentProcessModel {
  const {
    chat,
    thoughtText = "",
    thoughtStreaming = false,
    thoughtVersionLabel,
    thoughtVersionIndex = 0,
    thoughtVersionTotal = 0,
    nowMs = Date.now(),
  } = input;
  const loading = Boolean(chat.loading);
  const startedAtMs = parseMessageTimeMs(chat.startedAt);
  const finishedAtMs =
    parseMessageTimeMs(chat.finishedAt) ?? (loading ? undefined : nowMs);

  const thought = asText(thoughtText)
    ? {
      text: thoughtText,
      streaming: Boolean(thoughtStreaming),
      durationMs: undefined as number | undefined,
      durationLabel: undefined as string | undefined,
      versionLabel: thoughtVersionLabel,
      versionIndex: thoughtVersionIndex,
      versionTotal: thoughtVersionTotal,
    }
    : undefined;

  // 思考块耗时：优先用 startedAt → 首个工具 / finishedAt
  if (thought) {
    const firstStepStart = base.segments
      .flatMap((segment) => {
        if (segment.type === "group") {
          return segment.group.steps;
        }
        if (
          segment.type === "user_message" ||
          segment.type === "thinking" ||
          segment.type === "assistant_reply"
        ) {
          return [segment.step];
        }
        return [];
      })
      .map((step) => step.startedAtMs)
      .find((value) => !isNil(value));
    if (thought.streaming && !isNil(startedAtMs)) {
      thought.durationMs = Math.max(nowMs - startedAtMs, 0);
    } else if (!isNil(startedAtMs) && !isNil(firstStepStart) && firstStepStart >= startedAtMs) {
      thought.durationMs = firstStepStart - startedAtMs;
    } else if (!isNil(startedAtMs) && !isNil(finishedAtMs)) {
      thought.durationMs = Math.max(finishedAtMs - startedAtMs, 0);
    }
    thought.durationLabel = formatProcessDuration(thought.durationMs);
  }

  return {
    ...base,
    hasProcess: Boolean(thought || base.segments.length),
    thought,
    intentLine: extractIntentLine(thoughtText),
  };
}

/**
 * 把现有 chat.tasks / thought 投影为 Cursor 风格过程叙事模型。
 * 不改 SSE / ledger，只做展示层派生。
 *
 * 规则：
 * 1. SendUserMessage(user_brief) 永不折叠，并切开前后「执行了 N 步」
 * 2. 终答只走 chat.conclusion；过程文/CoT 不提升为独立终答段
 */
export function deriveAgentProcessModel(
  input: DeriveAgentProcessModelInput
): AgentProcessModel {
  const { chat, isPlanSolve, nowMs = Date.now(), ...thought } = input;
  return buildAgentProcessViewModel(
    deriveAgentProcessTaskModel({ chat, isPlanSolve, nowMs }),
    { chat, nowMs, ...thought }
  );
}
