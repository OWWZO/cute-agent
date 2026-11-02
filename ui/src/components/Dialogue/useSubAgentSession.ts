import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createFollowController } from "@/components/ChatView/followObservation";
import { buildAgentSessionStreamUrl } from "@/services/agentRun";
import { conversationHistoryApi } from "@/services/agentConversation";
import type {
  ConversationHistoryPage,
  ConversationRunReplay,
} from "@/services/agentConversation";
import {
  pickChildSessionRun,
  shouldFetchChildSessionReplay,
  type ChildSessionRun,
} from "@/utils/chat/subAgentChildSession";
import querySSE from "@/utils/querySSE";
import { parseAgentAnswer } from "@/utils/sseParsers";
import { FrameBuffer } from "@/utils/chat/frameBuffer";

/**
 * 子会话实时订阅 + 历史回放。
 *
 * 对齐 ZCode「详情打开才订、关闭即断」：enabled=false 时绝不建连，也绝不发请求。
 *
 * 两条数据源，按 run 状态组合（plan §5.4：heartbeat 不能冒充轨迹）：
 * <ul>
 *   <li>运行中 run：先拉一次单 run replay 打底（已落库的轮次只在账本里），
 *       再接子会话 SSE 补上「账本还没写完的那一段」。</li>
 *   <li>终态 run：没有活投影，SSE 立刻 follow_idle，只拉单 run replay（账本事件）。</li>
 * </ul>
 * 打底与实时帧天然不重叠：后端投影缓冲按落库边界清理，同一段增量不会出现两次。
 * <p>
 * <b>顺序上先 history 再决定建流</b>：终态 run 根本不开这条连接——它唯一的归宿就是
 * 立刻 follow_idle，建了纯属白跑一次往返、白起一个 emitter 与心跳任务。
 * 实时流从 {@code lastEventSeq=0} 订阅、投影自带全量回放，所以晚订阅不丢帧。
 * <p>
 * 观察流本身与主会话共用一套断流语义（{@link createFollowController}）：
 * {@code follow_pending} / {@code follow_idle} / 断流都会退避重订，而不是直接收流。
 * 后端提前注销投影时（本模块没有实测数据判定，两种候选都可能），前端自愈；
 * 重订次数用尽才熔断，交回账本兜底。
 */
export type SubAgentSessionState = {
  /** 详情是否就绪（首帧到达或历史已 hydrate） */
  ready: boolean;
  loading: boolean;
  error: string;
  /** 持久化帧链：追加只创建新节点，消费者按需遍历或转数组。 */
  frames: FrameBuffer<unknown>;
  /** 会话级历史（runs 摘要），决定 query / status / requestId */
  history: ConversationHistoryPage | null;
  /** 按 requestId 索引的单 run 回放（终态 run 的轨迹来源） */
  replays: Record<string, ConversationRunReplay>;
  /** 正在补拉的 requestId；供折叠条展示 loading */
  replayLoadingIds: string[];
  /** 数据源标记：history=历史回放，live=实时流 */
  source: "idle" | "history" | "live";
  /** 已用重订次数；供 UI 展示与测试断言 */
  reconnectAttempts: number;
  /** 重订耗尽（熔断）后的标记 */
  reconnectExhausted: boolean;
  /**
   * 瞬态：本次 patch 携带的「同组末帧」。mergePatch 据此清掉该组的中间 delta，
   * 不会写进最终 state（清理规则与后端投影同源）。
   */
  pruneFinalFrame?: unknown;
};

const IDLE_STATE: SubAgentSessionState = {
  ready: false,
  loading: false,
  error: "",
  frames: FrameBuffer.empty(),
  history: null,
  replays: {},
  replayLoadingIds: [],
  source: "idle",
  reconnectAttempts: 0,
  reconnectExhausted: false,
};

/**
 * 帧缓冲**不设条数上限**。
 * <p>
 * 缓冲只承载「账本还没写完的那一段」，长度天然有界于当前进行中的一次调用；按条数裁剪会在
 * 长思考 / 长工具流时丢掉前半段，而这段内容在落库前没有第二份副本。清理规则与后端投影
 * 同源：收到某组 {@code isFinal=true} 末帧后，该组中间 delta 才可移除。
 */
const FOLLOW_IDLE = "follow_idle";
const FOLLOW_PENDING = "follow_pending";
/** 同组末帧到达即可清中间 delta 的流式消息类型；分组键是 messageId。 */
const MESSAGE_GROUPED_STREAM_TYPES = new Set([
  "agent_stream",
  "llm_reasoning",
  "tool_thought",
]);
/** 同组末帧到达即可清中间帧的流式工具类型；分组键是 resultMap.toolCallId。 */
const TOOL_GROUPED_STREAM_TYPES = new Set([
  "deep_search",
  "browser",
  "code",
  "data_analysis",
  "knowledge",
]);
const SESSION_CONTROL_PACKAGES = new Set([
  "heartbeat",
  FOLLOW_IDLE,
  FOLLOW_PENDING,
]);

/**
 * 该帧所属的「落库分组」；null 表示不参与清理。
 * 与后端 {@code AgentStreamProjection#resolvePruneGroupKey} 保持同一套规则。
 */
function resolvePruneGroupKey(frame: unknown): string | null {
  if (!frame || typeof frame !== "object") {
    return null;
  }
  const envelope = frame as Record<string, unknown>;
  const resultMap = envelope.resultMap;
  const eventData =
    resultMap && typeof resultMap === "object"
      ? (resultMap as Record<string, unknown>).eventData
      : undefined;
  if (!eventData || typeof eventData !== "object") {
    return null;
  }
  const data = eventData as Record<string, unknown>;
  const messageType = String(data.messageType || "");
  if (MESSAGE_GROUPED_STREAM_TYPES.has(messageType)) {
    const messageId = String(data.messageId || "");
    return messageId ? `msg:${messageId}` : null;
  }
  if (TOOL_GROUPED_STREAM_TYPES.has(messageType)) {
    const nested = data.resultMap;
    const toolCallId =
      nested && typeof nested === "object"
        ? String((nested as Record<string, unknown>).toolCallId || "")
        : "";
    return toolCallId ? `tool:${toolCallId}` : null;
  }
  return null;
}

function isFinalEventFrame(frame: unknown): boolean {
  if (!frame || typeof frame !== "object") {
    return false;
  }
  const resultMap = (frame as Record<string, unknown>).resultMap;
  const eventData =
    resultMap && typeof resultMap === "object"
      ? (resultMap as Record<string, unknown>).eventData
      : undefined;
  return Boolean(
    eventData &&
      typeof eventData === "object" &&
      (eventData as Record<string, unknown>).isFinal === true
  );
}

function isControlFrame(frame: unknown): boolean {
  if (!frame || typeof frame !== "object") {
    return false;
  }
  return SESSION_CONTROL_PACKAGES.has(
    String((frame as { packageType?: unknown }).packageType || "")
  );
}

function isFollowIdleFrame(frame: unknown): boolean {
  if (!frame || typeof frame !== "object") {
    return false;
  }
  return String((frame as { packageType?: unknown }).packageType || "") === FOLLOW_IDLE;
}

function isFollowPendingFrame(frame: unknown): boolean {
  if (!frame || typeof frame !== "object") {
    return false;
  }
  return (
    String((frame as { packageType?: unknown }).packageType || "") === FOLLOW_PENDING
  );
}

/** follow_pending 携带的 retryMs；无效时返回 undefined 走指数退避。 */
function pendingRetryMs(frame: unknown): number | undefined {
  if (!frame || typeof frame !== "object") {
    return undefined;
  }
  const raw = Number((frame as { retryMs?: unknown }).retryMs);
  return Number.isFinite(raw) && raw > 0 ? raw : undefined;
}

function toMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 订阅侧副作用工厂（纯逻辑，便于测试）。
 * <p>
 * 抽成独立函数而不是写在 effect 里：测试需要断言「未启用时绝不调用 fetch / history」，
 * 也方便直接驱动帧回调而不依赖 React 运行时。
 *
 * @param pendingRequestId 刚提交、账本还没落库的 run。非空时**强制开实时流**：
 *   此刻 history 里最新的一条仍是上一轮的终态 run，按终态短路会把新 run 晾在
 *   `AgentRunLaunchGate.defer()` 里干等 2 秒兜底。
 * @returns abort 句柄
 */
export function subscribeSubAgentSession(
  childSessionId: string,
  enabled: boolean,
  onState: (patch: Partial<SubAgentSessionState>) => void,
  pendingRequestId?: string | null
): () => void {
  // 未启用 / 无 childSessionId：不发任何请求（含 history），对齐 ZCode「详情打开才订」
  if (!enabled || !childSessionId) {
    onState(IDLE_STATE);
    return () => {};
  }

  const controller = new AbortController();
  onState({
    ...IDLE_STATE,
    loading: true,
    error: ""
  });

  let frameCount = 0;
  let replayRequested = false;
  let historyRefreshed = false;
  let run: ChildSessionRun = undefined;
  /** 已收业务帧里的最大 eventSeq；重订时续游标，避免每次拿全量回放。 */
  let maxSeqSeen = 0;

  /** 终态 / 收流后无帧时的账本回放兜底。一次订阅只拉一次。 */
  const loadReplay = (requestId?: string) => {
    if (!requestId || replayRequested || controller.signal.aborted) {
      return;
    }
    replayRequested = true;
    conversationHistoryApi
      .getRunReplay(requestId)
      .then((replay) => {
        if (controller.signal.aborted) {
          return;
        }
        onState({
          ready: true,
          loading: false,
          replays: { [requestId]: replay },
          source: "history"
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        onState({ error: toMessage(error) });
      });
  };

  /**
   * 收口后重拉一次 history：run 的终态摘要与耗时只有账本里有，
   * 不刷就只能显示「工作中」定格，拿不到「已工作 12.9 秒」。
   */
  const refreshHistoryOnce = () => {
    if (historyRefreshed || controller.signal.aborted) {
      return;
    }
    historyRefreshed = true;
    conversationHistoryApi
      .getSessionDetail(childSessionId, { limit: 50 })
      .then((page) => {
        if (controller.signal.aborted) {
          return;
        }
        onState({ history: page });
      })
      .catch(() => {
        historyRefreshed = false;
      });
  };

  /**
   * 重订状态机：后端提前注销投影（`follow_idle`）或 registry 尚未就绪（`follow_pending`）
   * 时退避重订；次数用尽才熔断收口，与主会话观察流同构。
   */
  const follow = createFollowController({
    reconnect: () => openLiveStream(),
    onExhausted: () => {
      onState({
        reconnectExhausted: true,
        loading: false
      });
      // 重订耗尽：账本回放是最后一条路。只在一条业务帧都没收到时才拉 ——
      // 已有实时帧再补全量 replay，同一段轨迹会被 replay 与 frames 各走一遍，
      // 正文和工具卡都会重复。
      if (frameCount === 0) {
        loadReplay(run?.requestId);
      }
    },
  });

  /**
   * 订阅子会话流。必须走 querySSE —— 它统一注入 Authorization 并在 401 时刷新 token 重试；
   * 裸 fetch 不带凭证会被 AuthenticationFilter 直接挡成 401。
   * <p>
   * 从已收最大 eventSeq 起订阅（首次为 0）：投影自带全量回放，因此**晚订阅不会丢帧**，
   * 这才让「先问 history 再决定开不开流」成为安全的重排；重订时续游标只拿增量。
   */
  const openLiveStream = () => {
    if (controller.signal.aborted) {
      return;
    }
    querySSE(
      {
        method: "GET",
        body: null,
        signal: controller.signal,
        // GET 观察流不重发：断线恢复由本模块的重订状态机承担
        retryOnError: false,
        parser: parseAgentAnswer,
        handleMessage: (frame) => {
          if (isControlFrame(frame)) {
            if (isFollowIdleFrame(frame)) {
              if (frameCount === 0) {
                // 一帧轨迹都没拿到（订阅晚了 / 终态）：退账本回放。
                loadReplay(run?.requestId);
                return;
              }
              // 有帧但投影不在：后端可能只是提前注销，重订而不是收流。
              follow.scheduleReconnect(1000);
              return;
            }
            if (isFollowPendingFrame(frame)) {
              // registry 暂无但 ledger 仍 RUNNING：按后端给的间隔重订，别当任务结束。
              follow.scheduleReconnect(pendingRetryMs(frame));
            }
            return;
          }
          frameCount += 1;
          const seq = Number((frame as { eventSeq?: unknown }).eventSeq || 0);
          if (Number.isFinite(seq) && seq > maxSeqSeen) {
            maxSeqSeen = seq;
          }
          if ((frame as { finished?: unknown }).finished === true) {
            refreshHistoryOnce();
          }
          // 收到业务帧说明观察流已恢复，下一次断开从最短退避重新开始。
          follow.noteProgress();
          onState({
            ready: true,
            source: "live",
            frames: FrameBuffer.from([frame]),
            pruneFinalFrame: isFinalEventFrame(frame) ? frame : undefined,
            reconnectAttempts: follow.attempts()
          });
        },
        handleError: (error) => {
          if (controller.signal.aborted) {
            return;
          }
          onState({ error: toMessage(error) });
          follow.scheduleReconnect();
        },
        handleClose: () => {
          if (controller.signal.aborted) {
            return;
          }
          follow.scheduleReconnect();
        },
      },
      buildAgentSessionStreamUrl({
        sessionId: childSessionId,
        lastEventSeq: maxSeqSeen,
      })
    );
  };

  // 1) 先拉子会话 history（owner 只读校验由后端 GET /conversation/sessions/{id} 保证）。
  //    它同时决定「要不要建实时流」，所以必须先落地再决定，不能和流并行起。
  conversationHistoryApi
    .getSessionDetail(childSessionId, { limit: 50 })
    .then((page) => {
      if (controller.signal.aborted) {
        return;
      }
      run = pickChildSessionRun(page);
      onState({
        ready: true,
        history: page,
        source: "history"
      });
      // 账本回放是两种 run 共同的打底：终态 run 只有账本；运行中 run 的已落库轮次
      // 也只在账本里，实时流只剩「账本还没写完的那一段」（后端投影缓冲按落库边界清理）。
      // 不拉就会丢掉此前所有轮次，用户看到的就是「只剩最新一轮」。
      loadReplay(run?.requestId);
      // 终态 run：账本回放才是权威轨迹，实时流只会立刻 follow_idle。
      // 「回看已完成的子 Agent」正是详情面板的主要用法，没必要为它白建一条连接。
      // 例外：刚提交的新 run 还没落库，history 里的「最新」仍是上一轮终态 ——
      // 此时必须开流，后端 {@code AgentRunFollowApplicationService.attachSession}
      // 一收到观察者就会放行 defer 住的 launch。
      if (shouldFetchChildSessionReplay(run) && !pendingRequestId) {
        return;
      }
      openLiveStream();
    })
    .catch((error: unknown) => {
      if (controller.signal.aborted) {
        return;
      }
      onState({ error: toMessage(error) });
      // 拿不到 run 状态时不能连实时流也放弃，否则面板彻底空白。
      openLiveStream();
    });

  // dispose 必须先于 abort：否则卸载后定时器仍会触发一次 openLiveStream
  return () => {
    follow.dispose();
    controller.abort();
  };
}

export type SubAgentSessionController = SubAgentSessionState & {
  /** 幂等补拉某个 run 的账本回放：同一 requestId 只拉一次，失败不重试 */
  loadReplay: (requestId: string) => Promise<void>;
  /** 重订阅：重拉 history，并让这次订阅按 pendingRequestId 强制开流 */
  reload: (pendingRequestId?: string | null) => void;
};

export function useSubAgentSession(
  childSessionId: string,
  enabled: boolean
): SubAgentSessionController {
  const [state, setState] = useState<SubAgentSessionState>(IDLE_STATE);
  /**
   * 订阅序号 + 本次订阅要强开的 run。
   * 合成一个对象是为了让「重订阅」与「开流意图」同步生效：
   * 拆成两个 state 会让 effect 先跑一次旧的 pendingRequestId。
   */
  const [subscription, setSubscription] = useState<{
    seq: number;
    pendingRequestId: string | null;
  }>(() => ({
    seq: 0,
    pendingRequestId: null
  }));
  /** 已尝试补拉的 requestId：成功失败都不重试，与 `ReplayDisclosure` 同一套语义 */
  const attemptedReplaysRef = useRef<Set<string>>(new Set());

  const applyPatch = useCallback((patch: Partial<SubAgentSessionState>) => {
    setState((prev) => mergePatch(prev, patch));
  }, []);

  const loadReplay = useCallback((requestId: string) => {
    const id = String(requestId || "").trim();
    if (!id || attemptedReplaysRef.current.has(id)) {
      return Promise.resolve();
    }
    attemptedReplaysRef.current.add(id);
    const settle = (replay?: ConversationRunReplay) => {
      setState((prev) =>
        mergePatch(prev, {
          ...(replay ? { replays: { [id]: replay } } : {}),
          replayLoadingIds: prev.replayLoadingIds.filter((item) => item !== id)
        })
      );
    };
    setState((prev) =>
      mergePatch(prev, {
        replayLoadingIds: prev.replayLoadingIds.includes(id)
          ? prev.replayLoadingIds
          : [...prev.replayLoadingIds, id]
      })
    );
    return conversationHistoryApi
      .getRunReplay(id)
      .then(settle)
      .catch(() => settle());
  }, []);

  const reload = useCallback((pendingRequestId?: string | null) => {
    setSubscription((prev) => ({
      seq: prev.seq + 1,
      pendingRequestId: pendingRequestId ?? null
    }));
  }, []);

  useEffect(() => {
    const abort = subscribeSubAgentSession(
      childSessionId,
      enabled,
      applyPatch,
      subscription.pendingRequestId
    );
    return abort;
  }, [childSessionId, enabled, subscription, applyPatch]);

  return useMemo(
    () => ({
      ...state,
      loadReplay,
      reload
    }),
    [state, loadReplay, reload]
  );
}

export function mergePatch(
  prev: SubAgentSessionState,
  patch: Partial<SubAgentSessionState>
): SubAgentSessionState {
  const next: SubAgentSessionState = {
    ...prev,
    ...patch
  };
  // 瞬态字段不进 state：它只驱动本轮的清理
  delete next.pruneFinalFrame;
  // 回放按 requestId 增量合并：订阅侧的打底与用户点开的按需补拉分头写入
  if (patch.replays) {
    next.replays = {
      ...prev.replays,
      ...patch.replays
    };
  }
  // 帧以「增量追加」语义合并，避免调用方自己拼帧
  if (patch.frames && patch.frames.length > 0) {
    const appended = FrameBuffer.append(prev.frames, patch.frames);
    const finalGroup = resolvePruneGroupKey(patch.pruneFinalFrame);
    next.frames = finalGroup
      ? appended.filter(
        (frame) =>
          frame === patch.pruneFinalFrame ||
          resolvePruneGroupKey(frame) !== finalGroup
      )
      : appended;
  }
  if (patch.source !== "live") {
    next.source = next.source === "idle" ? "history" : next.source;
  }
  return next;
}

export default useSubAgentSession;
