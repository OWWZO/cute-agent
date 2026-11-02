/**
 * 会话观察流的退避重订状态机。
 * <p>
 * 从 {@link useConversationStream} 抽出：主会话与子会话详情面板共用同一套观察语义
 * —— `AgentSessionStreamController` 本来就是按 sessionId 寻址的单端点，前端却只在
 * 主会话侧做了重订。这里只含「何时重订、退避多久、何时熔断」，不含任何主会话特有的
 * HITL resume / 提交 query / 会话切换逻辑。</p>
 */

/** 主会话退避参数（useConversationStream.ts:259-267），两套观察流共用，不另立一套。 */
export const FOLLOW_BASE_DELAY_MS = 800;
export const FOLLOW_MAX_DELAY_MS = 15_000;
/** 连续重订上限（每收到一帧就清零）。用尽后熔断，不再排定时器。 */
export const FOLLOW_ATTEMPT_CAP = 20;

export type FollowController = {
  /** 排一次重订；delayMs 为空时按指数退避。已有在途重订时忽略本次调用。 */
  scheduleReconnect: (delayMs?: number) => void;
  /** 收到业务帧时调用：退避计数归零，下次断开从最短退避重新开始。 */
  noteProgress: () => void;
  /** 已用重订次数。 */
  attempts: () => number;
  /** 是否已熔断（次数用尽）。 */
  exhausted: () => boolean;
  /** 清理定时器；组件卸载时必须调用。 */
  dispose: () => void;
};

/**
 * 建一个重订状态机。
 *
 * @param reconnect 退避到点时重新打开观察流
 * @param onExhausted 次数用尽时的收口（停转圈 / 拉账本兜底 / 提示用户）
 */
export function createFollowController(handlers: {
  reconnect: () => void;
  onExhausted: () => void;
}): FollowController {
  // 用全局 setTimeout 而非 window.setTimeout：本模块是纯逻辑，单测跑在 node 环境（无 window）
  let timer: ReturnType<typeof setTimeout> | null = null;
  let attemptCount = 0;
  let capReached = false;
  let disposed = false;

  return {
    scheduleReconnect: (delayMs) => {
      // 已熔断 / 已卸载 / 已有在途重订：都不再叠加，否则同一会话会开出两条活流
      if (capReached || disposed || timer !== null) {
        return;
      }
      if (attemptCount >= FOLLOW_ATTEMPT_CAP) {
        // 后端已无投影时每次重挂都会立刻 EOF，无限退避只会把会话永久钉在「工作中」
        // （loading 恒为 true），且永远不再回查账本。交给调用方做最后一次裁决。
        capReached = true;
        handlers.onExhausted();
        return;
      }
      // 上面的守卫已保证 attemptCount < FOLLOW_ATTEMPT_CAP，这里直接自增即可；
      // 延迟的指数增长由 FOLLOW_MAX_DELAY_MS 在第 5 次（800 * 2^5）封顶。
      const retryDelay =
        delayMs ??
        Math.min(FOLLOW_BASE_DELAY_MS * 2 ** attemptCount, FOLLOW_MAX_DELAY_MS);
      attemptCount += 1;
      timer = setTimeout(() => {
        timer = null;
        handlers.reconnect();
      }, retryDelay);
    },
    noteProgress: () => {
      attemptCount = 0;
    },
    attempts: () => attemptCount,
    exhausted: () => capReached,
    dispose: () => {
      disposed = true;
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    },
  };
}
