import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import {FOLLOW_MAX_DELAY_MS,} from "@/components/ChatView/followObservation";
import {
  mergePatch,
  subscribeSubAgentSession,
  type SubAgentSessionState,
} from "./useSubAgentSession";

const mockGetSessionDetail = vi.fn();
const mockGetRunReplay = vi.fn();
const mockQuerySSE = vi.fn();

vi.mock("@/services/agentConversation", () => ({
  conversationHistoryApi: {
    getSessionDetail: (...args: unknown[]) => mockGetSessionDetail(...args),
    getRunReplay: (...args: unknown[]) => mockGetRunReplay(...args),
  },
}));

vi.mock("@/services/agentRun", () => ({
  buildAgentSessionStreamUrl: (params: { sessionId: string; lastEventSeq?: number }) =>
    `/api/agent/session/${encodeURIComponent(params.sessionId)}/stream?lastEventSeq=${
      params.lastEventSeq || 0
    }`,
}));

vi.mock("@/utils/querySSE", () => ({default: (...args: unknown[]) => mockQuerySSE(...args),}));

type Patch = Parameters<Parameters<typeof subscribeSubAgentSession>[2]>[0];

type SseConfig = {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  retryOnError?: boolean;
  handleMessage: (frame: unknown) => void;
  handleError: (error: Error) => void;
  handleClose: () => void;
};

function collect() {
  const patches: Patch[] = [];
  const push = (patch: Patch) => patches.push(patch);
  return {
    patches,
    push
  };
}

/** 取本次订阅传给 querySSE 的配置与 URL */
function lastSseCall() {
  const call = mockQuerySSE.mock.calls[mockQuerySSE.mock.calls.length - 1];
  return {
    config: call?.[0] as SseConfig,
    url: String(call?.[1])
  };
}

/**
 * 建流已改为「history 落地之后才决定」：先等流建立再取配置，
 * 否则拿到的是 undefined。这是契约本身的一部分，不要在测试里绕过。
 */
async function waitForStream() {
  await vi.waitFor(() => expect(mockQuerySSE).toHaveBeenCalledTimes(1));
  return lastSseCall();
}

function runningHistory() {
  return {
    sessionId: "sub_agent-1",
    latestRequestId: "req-1",
    runs: [{
      requestId: "req-1",
      status: "RUNNING",
      hasReplay: true
    }],
  };
}

function terminalHistory() {
  return {
    sessionId: "sub_agent-1",
    latestRequestId: "req-1",
    runs: [{
      requestId: "req-1",
      status: "SUCCESS",
      hasReplay: true
    }],
  };
}

describe("subscribeSubAgentSession", () => {
  beforeEach(() => {
    mockGetSessionDetail.mockReset();
    mockGetRunReplay.mockReset();
    mockQuerySSE.mockReset();
    mockGetSessionDetail.mockResolvedValue({
      sessionId: "sub_agent-1",
      runs: []
    });
    mockGetRunReplay.mockResolvedValue({
      requestId: "req-1",
      replayFrames: []
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("enabled=false 时既不建流也不拉 history", () => {
    const { patches, push } = collect();

    subscribeSubAgentSession("sub_agent-1", false, push);

    expect(patches).toHaveLength(1);
    expect(patches[0]).toMatchObject({
      ready: false,
      source: "idle"
    });
    expect(mockQuerySSE).not.toHaveBeenCalled();
    expect(mockGetSessionDetail).not.toHaveBeenCalled();
  });

  it("childSessionId 为空时同样不发起任何请求", () => {
    const { patches, push } = collect();

    subscribeSubAgentSession("", true, push);

    expect(patches).toHaveLength(1);
    expect(mockQuerySSE).not.toHaveBeenCalled();
    expect(mockGetSessionDetail).not.toHaveBeenCalled();
  });

  it("history 未落地前不建流，避免白建一条注定立刻 follow_idle 的连接", () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);

    // 建流被显式排在 history 之后（promise 回调只在微任务里跑，此处必然还没建）
    expect(mockQuerySSE).not.toHaveBeenCalled();
  });

  it("enabled 时按 GET 订阅子会话流", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);

    const { config, url } = await waitForStream();
    expect(url).toContain("/api/agent/session/sub_agent-1/stream");
    expect(config.method).toBe("GET");
    expect(config.signal).toBeInstanceOf(AbortSignal);
    expect(mockGetSessionDetail).toHaveBeenCalledTimes(1);
  });

  it("子会话流必须走带鉴权的 SSE 客户端，不得直连 fetch", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    await waitForStream();

    // 裸 fetch 不带 Authorization，会被后端 AuthenticationFilter 挡成 401
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(mockQuerySSE).toHaveBeenCalledTimes(1);
  });

  it("实时帧按序推送，控制帧不进轨迹缓冲", async () => {
    const { patches, push } = collect();

    const abort = subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();

    config.handleMessage({ packageType: "heartbeat" });
    config.handleMessage({ packageType: "follow_pending" });
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });

    await vi.waitFor(() => {
      expect(patches.some((patch) => patch.source === "live")).toBe(true);
    });
    // follow_pending 会排下重订定时器，必须 abort 清掉，否则泄漏到下一个用例
    abort();
    const livePatches = patches.filter((patch) => patch.source === "live");
    expect(livePatches).toHaveLength(1);
    expect(livePatches[0]?.frames?.[0]).toMatchObject({ packageType: "result" });
  });

  it("终态 run 走账本 replay，且根本不建实时流", async () => {
    mockGetSessionDetail.mockResolvedValue(terminalHistory());
    mockGetRunReplay.mockResolvedValue({
      requestId: "req-1",
      status: "SUCCESS",
      replayFrames: [{
        reqId: "req-1",
        resultMap: { eventData: {} }
      }],
    });
    const { patches, push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);

    await vi.waitFor(() => expect(mockGetRunReplay).toHaveBeenCalledWith("req-1"));
    expect(
      patches.some(
        (patch) =>
          patch.replays?.["req-1"] &&
          (patch.replays["req-1"] as { replayFrames: unknown[] }).replayFrames
            .length === 1
      )
    ).toBe(true);
    // 终态 run 的实时流只会立刻 follow_idle：这条连接是纯浪费，必须不建
    expect(mockQuerySSE).not.toHaveBeenCalled();
  });

  it("刚提交的 run 未落库时强制开流，不在终态 history 上短路", async () => {
    mockGetSessionDetail.mockResolvedValue(terminalHistory());
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push, "req-new");

    // history 里最新的一条仍是上一轮的 SUCCESS。若按终态短路，新 run 只能等后端
    // 2 秒兜底才启动，面板在这段时间里看不到任何反馈（这正是「发消息没反应」的根因）。
    const { url } = await waitForStream();
    expect(url).toContain("/api/agent/session/sub_agent-1/stream");
  });

  it("收到 finished 业务帧后重拉一次 history", async () => {
    mockGetSessionDetail.mockResolvedValue(runningHistory());
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    expect(mockGetSessionDetail).toHaveBeenCalledTimes(1);

    config.handleMessage({
      packageType: "result",
      finished: true,
      resultMap: { eventData: {} }
    });

    // 终态摘要与耗时只在账本里，不刷 history 就只能显示「工作中」定格
    await vi.waitFor(() => expect(mockGetSessionDetail).toHaveBeenCalledTimes(2));
  });

  it("运行中 run 也拉一次账本回放打底（已落库轮次只在账本里）", async () => {
    mockGetSessionDetail.mockResolvedValue(runningHistory());
    const { patches, push } = collect();

    const abort = subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });

    await vi.waitFor(() => {
      expect(patches.some((patch) => patch.source === "live")).toBe(true);
    });
    // 后端投影缓冲只保留「账本还没写完的那一段」，此前轮次必须从账本补齐，
    // 否则面板只剩最新一轮（这正是「刷新后早期工具调用消失」的根因）。
    await vi.waitFor(() => expect(mockGetRunReplay).toHaveBeenCalledWith("req-1"));
    abort();
  });

  it("follow_idle 且实时帧为空时补拉账本回放", async () => {
    mockGetSessionDetail.mockResolvedValue(runningHistory());
    const { patches, push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "follow_idle",
      finished: true
    });

    await vi.waitFor(() => expect(mockGetRunReplay).toHaveBeenCalledWith("req-1"));
    expect(patches.some((patch) => patch.source === "live")).toBe(false);
  });

  it("返回的 abort 句柄会中断订阅", async () => {
    const { push } = collect();

    const abort = subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    abort();

    expect(config.signal?.aborted).toBe(true);
  });

  it("history 失败不阻断流订阅", async () => {
    mockGetSessionDetail.mockRejectedValue(new Error("会话不存在"));
    const { patches, push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);

    await vi.waitFor(() => {
      expect(patches.some((patch) => patch.error?.includes("会话不存在"))).toBe(true);
    });
    // 拿不到 run 状态时不能连流都放弃，否则面板彻底空白
    expect(mockQuerySSE).toHaveBeenCalledTimes(1);
  });

  it("流异常时上报错误且不抛", async () => {
    const { patches, push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleError(new Error("SSE 请求失败，状态码: 500"));

    await vi.waitFor(() => {
      expect(patches.some((patch) => patch.error?.includes("500"))).toBe(true);
    });
  });

  it("abort 后的收流回调不再上报错误", async () => {
    const { patches, push } = collect();

    const abort = subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    abort();
    config.handleClose();
    config.handleError(new Error("boom"));

    expect(patches.some((patch) => patch.error?.includes("boom"))).toBe(false);
  });
});

describe("subscribeSubAgentSession 观察流重订", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockGetSessionDetail.mockReset();
    mockGetRunReplay.mockReset();
    mockQuerySSE.mockReset();
    mockGetSessionDetail.mockResolvedValue(runningHistory());
    mockGetRunReplay.mockResolvedValue({
      requestId: "req-1",
      replayFrames: []
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("follow_pending(retryMs=800) 后 800ms 重新调用 querySSE", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    expect(mockQuerySSE).toHaveBeenCalledTimes(1);

    config.handleMessage({
      packageType: "follow_pending",
      retryMs: 800
    });

    vi.advanceTimersByTime(799);
    expect(mockQuerySSE).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1);
    expect(mockQuerySSE).toHaveBeenCalledTimes(2);
  });

  it("follow_idle 且 frameCount > 0 时触发重订，而非立即结束", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });
    config.handleMessage({
      packageType: "follow_idle",
      finished: true
    });

    // 1s 是本模块给 follow_idle 的固定重订间隔
    vi.advanceTimersByTime(999);
    expect(mockQuerySSE).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1);
    expect(mockQuerySSE).toHaveBeenCalledTimes(2);
  });

  it("follow_idle 且 frameCount === 0 时仍拉账本回放（不回归）", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "follow_idle",
      finished: true
    });

    await vi.waitFor(() => expect(mockGetRunReplay).toHaveBeenCalledWith("req-1"));
    vi.advanceTimersByTime(15_000);
    expect(mockQuerySSE).toHaveBeenCalledTimes(1);
  });

  it("断流也走重订，而不是直接拉账本回放", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });
    config.handleClose();

    // 退避 800ms（无显式 retryMs）
    vi.advanceTimersByTime(800);
    expect(mockQuerySSE).toHaveBeenCalledTimes(2);
  });

  it("连续 20 次重订失败后熔断，不再发起第 21 次", async () => {
    const { patches, push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });

    for (let i = 0; i < 20; i += 1) {
      // 每次都拿最新那次订阅的配置：新连接会重新触发 follow_idle
      config.handleClose();
      vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS);
    }

    // 第 21 次：熔断，不排定时器
    config.handleClose();
    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS * 2);

    expect(mockQuerySSE).toHaveBeenCalledTimes(21);
    expect(
      patches.some(
        (patch) =>
          patch.reconnectExhausted === true &&
          patch.loading === false
      )
    ).toBe(true);
  });

  it("熔断且 frameCount === 0 时补拉账本回放", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();

    for (let i = 0; i < 20; i += 1) {
      config.handleClose();
      vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS);
    }
    config.handleClose();

    await vi.waitFor(() => expect(mockGetRunReplay).toHaveBeenCalledWith("req-1"));
  });

  it("熔断时不再额外拉 replay（打底那次已在订阅开始时拉过）", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });
    // 订阅开始时已拉过一次打底
    await vi.waitFor(() => expect(mockGetRunReplay).toHaveBeenCalledTimes(1));
    mockGetRunReplay.mockClear();

    for (let i = 0; i < 20; i += 1) {
      config.handleClose();
      vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS);
    }
    config.handleClose();
    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS * 2);

    // 熔断收口不再重复拉：replay 只负责「已落库的历史」，重复拉会与已有帧重复渲染
    expect(mockGetRunReplay).not.toHaveBeenCalled();
  });

  it("重订时 URL 带已收最大 eventSeq，不是 0", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    expect(lastSseCall().url).toContain("lastEventSeq=0");

    config.handleMessage({
      packageType: "result",
      eventSeq: 7,
      resultMap: { eventData: {} }
    });
    config.handleClose();
    vi.advanceTimersByTime(800);

    expect(mockQuerySSE).toHaveBeenCalledTimes(2);
    expect(lastSseCall().url).toContain("lastEventSeq=7");
  });

  it("收到业务帧后 attempts 归零，下一次断线从最短退避开始", async () => {
    const { push } = collect();

    subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();

    config.handleClose();
    vi.advanceTimersByTime(800);
    config.handleClose();
    vi.advanceTimersByTime(1600);
    expect(mockQuerySSE).toHaveBeenCalledTimes(3);

    // 新连接收到业务帧 → 退避计数归零
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });
    config.handleClose();
    vi.advanceTimersByTime(799);
    expect(mockQuerySSE).toHaveBeenCalledTimes(3);
    vi.advanceTimersByTime(1);
    expect(mockQuerySSE).toHaveBeenCalledTimes(4);
  });

  it("卸载后定时器不再触发重订", async () => {
    const { push } = collect();

    const abort = subscribeSubAgentSession("sub_agent-1", true, push);
    const { config } = await waitForStream();
    config.handleMessage({
      packageType: "result",
      resultMap: { eventData: {} }
    });
    config.handleClose();
    abort();

    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS * 2);

    expect(mockQuerySSE).toHaveBeenCalledTimes(1);
  });
});

describe("mergePatch 帧缓冲清理", () => {
  const IDLE: SubAgentSessionState = {
    ready: false,
    loading: false,
    error: "",
    frames: [],
    history: null,
    replays: {},
    replayLoadingIds: [],
    source: "idle",
    reconnectAttempts: 0,
    reconnectExhausted: false,
  };

  /** 一轮 LLM 的流式帧：messageId 分组，isFinal 表示该轮已落库。 */
  const llmFrame = (messageId: string, text: string, isFinal: boolean) => ({
    packageType: "result",
    resultMap: {
      eventData: {
        messageId,
        messageType: "agent_stream",
        isFinal,
        result: text,
      },
    },
  });

  /** 流式工具帧：toolCallId 分组。 */
  const toolFrame = (toolCallId: string, isFinal: boolean, text: string) => ({
    packageType: "result",
    resultMap: {
      eventData: {
        messageId: `${toolCallId}-${text}`,
        messageType: "deep_search",
        isFinal,
        result: text,
        resultMap: { toolCallId },
      },
    },
  });

  const push = (state: typeof IDLE, frame: unknown) =>
    mergePatch(state, {
      ready: true,
      source: "live",
      frames: [frame],
      pruneFinalFrame: isFinalFrame(frame) ? frame : undefined,
    });

  const isFinalFrame = (frame: unknown) =>
    Boolean(
      (frame as { resultMap?: { eventData?: { isFinal?: boolean } } }).resultMap
        ?.eventData?.isFinal === true
    );

  const texts = (state: { frames: unknown[] }) =>
    state.frames.map(
      (frame) =>
        (frame as { resultMap: { eventData: { result: string } } }).resultMap
          .eventData.result
    );

  it("流式未结束时帧全部保留，不按条数截断", () => {
    let state = IDLE;
    for (let i = 0; i < 700; i += 1) {
      state = push(state, llmFrame("msg-1", `delta-${i}`, false));
    }

    // 700 帧远超旧的 512 上限：长思考不能在落库前丢掉前半段
    expect(state.frames).toHaveLength(700);
    expect(texts(state)[0]).toBe("delta-0");
  });

  it("收到某组末帧后只清该组中间 delta，末帧保留", () => {
    let state = IDLE;
    state = push(state, llmFrame("msg-1", "a", false));
    state = push(state, llmFrame("msg-1", "b", false));
    state = push(state, llmFrame("msg-1", "ab", true));

    expect(texts(state)).toEqual(["ab"]);
  });

  it("一组收口不影响另一组仍在流式的内容", () => {
    let state = IDLE;
    state = push(state, llmFrame("msg-1", "a", false));
    state = push(state, llmFrame("msg-2", "x", false));
    state = push(state, llmFrame("msg-2", "y", false));
    state = push(state, llmFrame("msg-1", "a", true));

    expect(texts(state)).toEqual(["x", "y", "a"]);
  });

  it("流式工具按 toolCallId 分组清理", () => {
    let state = IDLE;
    state = push(state, toolFrame("call-1", false, "search"));
    state = push(state, toolFrame("call-1", false, "extend"));
    state = push(state, toolFrame("call-2", false, "other"));
    state = push(state, toolFrame("call-1", true, "final"));

    expect(texts(state)).toEqual(["other", "final"]);
  });

  it("pruneFinalFrame 是瞬态，不写进 state", () => {
    const state = push(IDLE, llmFrame("msg-1", "a", true));
    expect("pruneFinalFrame" in state).toBe(false);
  });

  it("回放按 requestId 增量合并，后到的不会冲掉已有的", () => {
    const first = mergePatch(IDLE, {
      replays: {
        "req-1": {
          requestId: "req-1",
          status: "SUCCESS",
          replayFrames: []
        }
      }
    });
    const second = mergePatch(first, {
      replays: {
        "req-2": {
          requestId: "req-2",
          status: "SUCCESS",
          replayFrames: []
        }
      }
    });

    // 订阅侧打底与用户点开的按需补拉分头写入，谁也不能覆盖谁
    expect(Object.keys(second.replays)).toEqual(["req-1", "req-2"]);
  });
});
