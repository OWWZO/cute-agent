/**
 * 「用户轮次」（turn）分组。
 *
 * 背景：`AskUserQuestion` / `ExitPlanMode` 这类 yield 工具会**结束当前 run**，
 * 用户回答后由 continuation run 继续。后端给 continuation 的 requestId 加
 * `resume_` 前缀（`AskUserQuestionApplicationService`），于是前端拿到的 chatList
 * 是「一个提问 → 两个 run」。若按 run 逐个渲染，就会出现两条「已工作 X」，
 * 与 ZCode 的 turn 语义不一致（ZCode 的 `splitVisualWorkSegments` 只在
 * `guided` 插话时切段，HITL 回答不切）。
 *
 * 这里把同一提问下的多个 run 归成一个 turn，并合并成单个 chat 交给 `Dialogue`，
 * 让整轮只渲染一条状态条、一个过程区。
 */

/** 后端 continuation run 的 requestId 前缀。 */
const RESUME_REQUEST_ID_PREFIX = "resume_";

export type ChatTurn = {
  /** turn 的稳定 key（= 首个 run 的 requestId），用于 React key，不随 continuation 追加而变。 */
  key: string;
  /** turn 内的 run，按时间顺序。`chats[0]` 是带用户提问的首个 run。 */
  chats: CHAT.ChatItem[];
};

export type ChatTurnView = {
  key: string;
  chat: CHAT.ChatItem;
  requestIds: string[];
  pendingReplayIds: string[];
};

/**
 * 该 run 是否是「续跑」（用户回答 HITL 后由后端发起的 continuation run）。
 *
 * 主判据是 requestId 前缀；`query` 为空作为兜底 —— 续跑没有新的用户提问正文，
 * 但**不能**反过来只看 query（历史数据里首个 run 的 query 也可能被覆盖成空）。
 */
export function isContinuationRun(chat: CHAT.ChatItem): boolean {
  const requestId = String(chat.requestId || "");
  if (requestId.startsWith(RESUME_REQUEST_ID_PREFIX)) {
    return true;
  }
  return !String(chat.query || "").trim();
}

/**
 * 把 chatList 切成 turn。首个 run（或任何非续跑 run）开启新 turn，
 * 其后的续跑 run 归入当前 turn。
 */
const turnCache = new WeakMap<
  CHAT.ChatItem,
  { chats: CHAT.ChatItem[]; turn: ChatTurn }
>();
const mergedTurnCache = new WeakMap<ChatTurn, CHAT.ChatItem>();
const turnViewCache = new WeakMap<ChatTurn, ChatTurnView>();

export function groupChatsIntoTurns(
  chats: readonly CHAT.ChatItem[]
): ChatTurn[] {
  const turns: ChatTurn[] = [];
  let currentChats: CHAT.ChatItem[] = [];
  const flush = (cacheable: boolean) => {
    if (!currentChats.length) return;
    const first = currentChats[0];
    const cached = cacheable ? turnCache.get(first) : undefined;
    const sameChats = cached &&
      cached.chats.length === currentChats.length &&
      cached.chats.every((chat, index) => chat === currentChats[index]);
    let turn: ChatTurn;
    if (sameChats) {
      turn = cached.turn;
    } else {
      turn = {
        key: String(first.requestId || ""),
        chats: currentChats,
      };
      if (cacheable) {
        turnCache.set(first, {
          chats: currentChats,
          turn,
        });
      }
    }
    turns.push(turn);
    currentChats = [];
  };

  for (const chat of chats) {
    if (currentChats.length && !isContinuationRun(chat)) {
      flush(currentChats.every((item) => !item.loading));
    }
    currentChats.push(chat);
  }
  // 末轮可能正处于流式状态，即使 chat 对象被原地更新，也必须重新合并它。
  flush(false);
  return turns;
}

/**
 * turn 内**仍需补历史回放**的 run。
 *
 * 历史接口只给轻量摘要壳（`tasks` 为空），真正的过程数据要按 run 单独拉回放。
 * 合并后的 turn 只有一个 requestId（最后一个 run），所以调用方必须按 turn 取候选，
 * 否则 AskUserQuestion 之前那个 run 的回放永远补不上 —— 它的工具卡会整段消失。
 */
export function collectPendingReplayIds(
  chats: readonly CHAT.ChatItem[]
): string[] {
  return chats
    .filter(
      (chat) =>
        chat.requestId &&
        chat.replayLoaded !== true &&
        chat.replayAvailable !== false
    )
    .map((chat) => String(chat.requestId));
}

export function buildChatTurnView(turn: ChatTurn): ChatTurnView {
  const cached = turnViewCache.get(turn);
  if (cached && !turn.chats.some((chat) => chat.loading)) {
    return cached;
  }
  const view = {
    key: turn.key,
    chat: mergeTurnChats(turn),
    requestIds: turn.chats
      .map((item) => String(item.requestId || ""))
      .filter(Boolean),
    pendingReplayIds: collectPendingReplayIds(turn.chats),
  };
  if (!turn.chats.some((chat) => chat.loading)) {
    turnViewCache.set(turn, view);
  }
  return view;
}

/**
 * 把 turn 内的多个 run 合并成一个 chat，供 `Dialogue` 单 chat 渲染。
 *
 * 取舍：
 * - `query` / `files` 取**首个 run**（用户消息与附件挂在提问上）；
 * - `requestId` 取**最后一个 run** —— 它是「当前状态」的代表，
 *   `streamingThoughtMap` / `replayLoadingIds` / `onUndo` 都按最新 run 索引；
 * - 过程数据（`tasks` / `timeline` / `multiAgent.tasks`）**按顺序拼接**；
 * - 终态字段（`response` / `conclusion` / `metrics` / `tip`）取最后一个 run；
 * - `loading` / `forceStop` 取「任一」，避免续跑中误判为已完成；
 * - `runDurationMs` 求和，让「已工作 X」覆盖整轮而不是只覆盖一个 run。
 */
export function mergeTurnChats(turn: ChatTurn): CHAT.ChatItem {
  const cached = mergedTurnCache.get(turn);
  if (cached) {
    return cached;
  }
  const { chats } = turn;
  if (chats.length === 1) {
    mergedTurnCache.set(turn, chats[0]);
    return chats[0];
  }

  const first = chats[0];
  const last = chats[chats.length - 1];
  const totalDuration = chats.reduce(
    (total, chat) => total + (Number(chat.runDurationMs) || 0),
    0
  );

  const merged = {
    ...last,
    // 身份：代表「当前状态」的最后一个 run
    requestId: last.requestId,
    // 用户侧内容：挂在提问所在的第一个 run
    query: first.query,
    files: first.files,
    generatedFiles: chats.flatMap((chat) => chat.generatedFiles || []),
    // 过程数据按 run 顺序拼接
    tasks: chats.flatMap((chat) => chat.tasks || []),
    timeline: chats.flatMap((chat) => chat.timeline || []),
    multiAgent: {
      ...last.multiAgent,
      tasks: chats.flatMap((chat) => chat.multiAgent?.tasks || []),
    },
    thought: chats
      .map((chat) => String(chat.thought || "").trim())
      .filter(Boolean)
      .join("\n\n"),
    // 终态取最后一个 run
    response: last.response,
    conclusion: last.conclusion,
    metrics: last.metrics,
    tip: last.tip,
    taskStatus: last.taskStatus,
    // 聚合态
    loading: chats.some((chat) => chat.loading),
    forceStop: chats.some((chat) => chat.forceStop),
    startedAt: first.startedAt,
    finishedAt: last.finishedAt,
    runDurationMs: totalDuration > 0 ? totalDuration : undefined,
    replayAvailable: chats.some((chat) => chat.replayAvailable),
    replayLoaded: chats.every((chat) => chat.replayLoaded),
  };
  mergedTurnCache.set(turn, merged);
  return merged;
}
