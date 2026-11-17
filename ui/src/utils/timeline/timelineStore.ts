import type { AgentTimelineItem } from "@/types/timeline";
import type { ConversationReplayFrame } from "@/services/agentConversation";

/**
 * 主 Agent 与子 Agent 共用的时间线合并规则。
 *
 * committed item 和 live transient item 都以 itemId 为唯一身份；没有 itemId 的旧帧
 * 只在 legacy adapter 中按顺序消费，不能用来伪造新的持久化身份。
 */
export class TimelineStore {
  private readonly items = new Map<string, AgentTimelineItem>();

  constructor(initial: AgentTimelineItem[] = []) {
    this.upsertMany(initial);
  }

  upsertMany(items: AgentTimelineItem[] | undefined): void {
    for (const item of items || []) {
      if (!item) {
        continue;
      }
      const key = timelineItemKey(item);
      if (!key) {
        continue;
      }
      const previous = this.items.get(key);
      const normalized = mergeDefined(previous, item);
      normalized.itemId = key;
      normalized.delta = item.eventType === "item.delta" && item.delta
        ? `${previous?.delta || ""}${item.delta}`
        : previous?.delta || item.delta || null;
      normalized.payload = item.payload
        ? { ...(previous?.payload || {}), ...item.payload }
        : previous?.payload;
      normalized.responseAll = item.responseAll || previous?.responseAll;
      this.items.set(key, normalized);
    }
  }

  values(): AgentTimelineItem[] {
    return [...this.items.values()].sort(compareTimelineItems);
  }
}

function mergeDefined(
  previous: AgentTimelineItem | undefined,
  next: AgentTimelineItem
): AgentTimelineItem {
  const merged: AgentTimelineItem = { ...(previous || {}) };
  for (const [key, value] of Object.entries(next)) {
    if (value !== undefined) {
      (merged as unknown as Record<string, unknown>)[key] = value;
    }
  }
  return merged;
}

export function timelineItemKey(item: AgentTimelineItem): string {
  return String(item.itemId || "").trim();
}

export function compareTimelineItems(
  left: AgentTimelineItem,
  right: AgentTimelineItem
): number {
  const order = Number(left.orderKey || left.seq || 0) - Number(right.orderKey || right.seq || 0);
  if (order !== 0) {
    return order;
  }
  const subOrder = Number(left.subOrder || 0) - Number(right.subOrder || 0);
  if (subOrder !== 0) {
    return subOrder;
  }
  return String(left.itemId || "").localeCompare(String(right.itemId || ""));
}

/** 只有 run-final 拥有底部终答身份。 */
export function isRunFinalTimelineItem(
  item: Pick<AgentTimelineItem, "itemId" | "kind" | "phase"> | undefined
): boolean {
  return Boolean(
    item
      && item.phase === "FINAL"
      && item.kind === "ASSISTANT_MESSAGE"
      && String(item.itemId || "").startsWith("run:")
      && String(item.itemId || "").endsWith(":final")
  );
}

export function isRunTimelineTerminal(status?: string): boolean {
  return ["SUCCESS", "FAILED", "STOPPED", "TIMEOUT", "WAITING_INPUT"].includes(
    String(status || "").toUpperCase()
  );
}

/** 只有真正交付最终答复的 run 状态才允许产生 conclusion。 */
export function isRunFinalStatus(status?: string): boolean {
  return ["SUCCESS", "FAILED", "STOPPED", "TIMEOUT"].includes(
    String(status || "").toUpperCase()
  );
}

export function isRunFinalReplayFrame(frame: ConversationReplayFrame | undefined): boolean {
  if (!frame) {
    return false;
  }
  if (frame.eventType === "run.completed"
      || frame.eventType === "run.failed"
      || frame.eventType === "run.stopped") {
    return true;
  }
  if (!frame.itemId) {
    return false;
  }
  return isRunFinalTimelineItem({
    itemId: frame.itemId,
    kind: frame.kind,
    phase: frame.phase,
  });
}

/**
 * live SSE 的兼容判定：
 * - 已带 canonical itemId 时，只允许 run-final 成为终答；
 * - 旧后端帧没有 itemId 时返回 true，继续由 legacy 链路判定。
 */
export function isConclusionEligibleLiveFrame(frame: unknown): boolean {
  if (!frame || typeof frame !== "object") {
    return true;
  }
  const source = frame as Record<string, unknown>;
  const itemId = String(source.itemId || "");
  if (!itemId) {
    return true;
  }
  return isRunFinalTimelineItem({
    itemId,
    kind: typeof source.kind === "string"
      ? source.kind as AgentTimelineItem["kind"]
      : undefined,
    phase: typeof source.phase === "string"
      ? source.phase as AgentTimelineItem["phase"]
      : undefined,
  });
}
