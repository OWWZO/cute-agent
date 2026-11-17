import type {
  ConversationReplayFrame,
} from "@/services/agentConversation";
import type { AgentTimelineItem } from "@/types/timeline";
import { timelineItemsToReplayFrames } from "@/utils/timelineItems";
import { applyTimelineFrameToChat } from "./timelineFrameProjection";
import { identityKeys, readTaskIdentity } from "./taskIdentity";

type TimelineProjectionOptions = {
  /** 旧 replayFrames 没有 itemId 时是否允许 result 兼容成结论。 */
  allowLegacyResult: boolean;
  /** 是否让 result 只合并文件元数据，不覆盖已经形成的结论。 */
  settleResultMetadata: boolean;
  /**
   * 独立会话（真实子 Agent）需要剥离指向父会话 Agent 卡的父链标签；
   * 主会话保持原有嵌套结构。
   */
  isolatedSession?: boolean;
};

type RenderableTimelineEvent = {
  frame: ConversationReplayFrame;
  eventData: MESSAGE.EventData;
};

const PARENT_ID_KEYS = ["parentToolUseId", "parentToolCallId"] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function readFrameParentId(eventData: MESSAGE.EventData): string {
  const source = eventData as unknown as Record<string, unknown>;
  const resultMap = isRecord(source.resultMap) ? source.resultMap : undefined;
  const nested = isRecord(resultMap?.resultMap) ? resultMap.resultMap : undefined;
  const toolResult = isRecord(source.toolResult) ? source.toolResult : undefined;
  return String(
    resultMap?.parentToolUseId ??
      resultMap?.parentToolCallId ??
      nested?.parentToolUseId ??
      nested?.parentToolCallId ??
      source.parentToolUseId ??
      toolResult?.parentToolUseId ??
      ""
  ).trim();
}

function removeParentIdKeys(target: Record<string, unknown>): void {
  for (const key of PARENT_ID_KEYS) {
    delete target[key];
  }
}

function detachExternalParentTags(
  eventData: MESSAGE.EventData,
  ownKeys: Set<string>
): MESSAGE.EventData {
  const parentId = readFrameParentId(eventData);
  if (!parentId || ownKeys.has(parentId)) {
    return eventData;
  }
  const source = eventData as unknown as Record<string, unknown>;
  const next: Record<string, unknown> = { ...source };
  removeParentIdKeys(next);

  const resultMap = isRecord(source.resultMap) ? { ...source.resultMap } : undefined;
  if (resultMap) {
    removeParentIdKeys(resultMap);
    const nested = isRecord(resultMap.resultMap) ? { ...resultMap.resultMap } : undefined;
    if (nested) {
      removeParentIdKeys(nested);
      resultMap.resultMap = nested;
    }
    next.resultMap = resultMap;
  }

  const toolResult = isRecord(source.toolResult) ? { ...source.toolResult } : undefined;
  if (toolResult) {
    removeParentIdKeys(toolResult);
    next.toolResult = toolResult;
  }

  return next as unknown as MESSAGE.EventData;
}

function collectOwnIdentityKeys(events: RenderableTimelineEvent[]): Set<string> {
  const keys = new Set<string>();
  for (const event of events) {
    for (const key of identityKeys(
      readTaskIdentity(event.eventData as unknown as Record<string, unknown>)
    )) {
      keys.add(key);
    }
  }
  return keys;
}

function toRenderableEvents(
  items: AgentTimelineItem[] | undefined,
  replayFrames: ConversationReplayFrame[] | undefined
): RenderableTimelineEvent[] {
  const itemFrames = timelineItemsToReplayFrames(items);
  const frames = itemFrames.length ? itemFrames : replayFrames || [];
  const events: RenderableTimelineEvent[] = [];
  for (const frame of frames) {
    const eventData = frame.resultMap?.eventData;
    if (eventData) {
      events.push({ frame, eventData });
    }
  }
  return events;
}

/**
 * 主 Agent 与子 Agent 共用的时间线投影。
 *
 * committed timelineItems 优先；只有旧 run 没有 canonical item 时才读取 replayFrames。
 * canonical 项只有 `run:<requestId>:final` 可以写 conclusion。
 */
export function applyTimelineSourcesToChat(
  chat: CHAT.ChatItem,
  input: {
    items?: AgentTimelineItem[];
    replayFrames?: ConversationReplayFrame[];
  } & TimelineProjectionOptions
): CHAT.Task | undefined {
  const events = toRenderableEvents(input.items, input.replayFrames);
  const ownKeys = input.isolatedSession ? collectOwnIdentityKeys(events) : undefined;
  let conclusion: CHAT.Task | undefined;

  for (const event of events) {
    const eventData = ownKeys
      ? detachExternalParentTags(event.eventData, ownKeys)
      : event.eventData;
    const applied = applyTimelineFrameToChat(chat, event.frame, eventData, {
      allowLegacyResult: input.allowLegacyResult,
      settleResultMetadata: input.settleResultMetadata,
      clearCanonicalProcessConclusion: true,
    });
    if (applied) {
      conclusion = applied;
    }
  }
  return conclusion;
}

export default applyTimelineSourcesToChat;
