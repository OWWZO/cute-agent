import type { ConversationReplayFrame } from "@/services/agentConversation";
import { isRunFinalReplayFrame } from "@/utils/timeline/timelineStore";
import {
  buildTaskFromEventData,
  combineData,
  settleRootResultConclusion,
} from "@/utils/chat";

type ApplyTimelineFrameOptions = {
  /** 无 itemId 的旧 replay 帧是否允许 result 兼容成结论。 */
  allowLegacyResult: boolean;
  /** result 只合并文件元数据，不覆盖已由流式正文形成的结论。 */
  settleResultMetadata: boolean;
  /** canonical 过程帧不能把 combineData 暂存的正文升级成底部结论。 */
  clearCanonicalProcessConclusion: boolean;
};

/**
 * 主/子 Agent 共用的单帧投影规则。
 *
 * <p>canonical 帧只有 `run:<requestId>:final` 能写 conclusion；旧 replay 帧在
 * 没有 itemId 时才允许兼容 result。其它过程事件仍完整交给 combineData。</p>
 */
export function applyTimelineFrameToChat(
  chat: CHAT.ChatItem,
  frame: ConversationReplayFrame,
  eventData: MESSAGE.EventData,
  options: ApplyTimelineFrameOptions
): CHAT.Task | undefined {
  const innerType = String(
    eventData.resultMap?.messageType || eventData.messageType || ""
  );
  const resultEligible = frame.itemId
    ? isRunFinalReplayFrame(frame)
    : options.allowLegacyResult;

  if (innerType === "result" && !resultEligible) {
    return undefined;
  }

  combineData(eventData, chat);
  if (innerType !== "result") {
    if (options.clearCanonicalProcessConclusion && frame.itemId) {
      chat.conclusion = undefined;
    }
    return undefined;
  }

  if (options.settleResultMetadata && settleRootResultConclusion(chat, eventData)) {
    return chat.conclusion;
  }
  const conclusion = buildTaskFromEventData(eventData) as unknown as CHAT.Task;
  chat.conclusion = conclusion;
  return conclusion;
}
