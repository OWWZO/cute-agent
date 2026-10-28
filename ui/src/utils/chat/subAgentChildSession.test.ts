import { describe, expect, it } from "vitest";
import {
  buildChildSessionChat,
  buildChildSessionRounds,
  isTerminalRunStatus,
  pickChildSessionRun,
  readChildSessionEventData,
  shouldFetchChildSessionReplay,
} from "./subAgentChildSession";
import type {
  ConversationHistoryPage,
  ConversationRunReplay,
} from "@/services/agentConversation";
import { resolveTaskToolCallId } from "./toolCalls";

function eventData(
  resultMap: Record<string, unknown>,
  overrides: Partial<MESSAGE.EventData> = {}
): MESSAGE.EventData {
  return {
    messageOrder: 1,
    messageType: "task",
    messageId: "m1",
    taskId: "t1",
    taskOrder: 1,
    resultMap: resultMap as unknown as MESSAGE.Task,
    ...overrides,
  } as unknown as MESSAGE.EventData;
}

function frameOf(data: unknown) {
  return { resultMap: { eventData: data } };
}

/**
 * 账本回放的工具帧真实形态：外层 `eventData.resultMap` 是 envelope，
 * 内层 `resultMap.resultMap` 才是工具 payload，两层都带父链标签
 * （后端 mirrorNestingToOuter 会把 parentToolUseId 镜像到外层）。
 */
function replayToolEvent(
  messageType: "tool_call" | "tool_result",
  payload: Record<string, unknown>,
  messageId: string
): MESSAGE.EventData {
  return eventData(
    {
      messageType,
      messageId,
      isFinal: true,
      parentToolUseId: payload.parentToolUseId,
      resultMap: payload,
    },
    { messageId }
  );
}

/** 在派生后的时间线里按 toolCallId 找任务（含嵌套 children）。 */
function findTaskByToolCallId(
  chat: CHAT.ChatItem,
  toolCallId: string
): CHAT.Task | undefined {
  const visit = (task: CHAT.Task | undefined): CHAT.Task | undefined => {
    if (!task) {
      return undefined;
    }
    const resolved = resolveTaskToolCallId(task);
    if (resolved === toolCallId) {
      return task;
    }
    for (const child of task.children || []) {
      const hit = visit(child);
      if (hit) {
        return hit;
      }
    }
    return undefined;
  };
  for (const group of chat.tasks || []) {
    for (const container of group || []) {
      const hit = visit(container as CHAT.Task);
      if (hit) {
        return hit;
      }
    }
  }
  return undefined;
}

function historyPage(
  runs: ConversationHistoryPage["runs"],
  latestRequestId?: string
): ConversationHistoryPage {
  return {
    sessionId: "sub_1",
    title: "",
    status: "",
    deepThink: false,
    runCount: runs.length,
    finishedRunCount: 0,
    failedRunCount: 0,
    latestRequestId,
    runs,
  };
}

function replayOf(
  requestId: string,
  frames: unknown[],
  status = "SUCCESS"
): ConversationRunReplay {
  return {
    requestId,
    status,
    replayFrames: frames.map((frame) => ({
      reqId: requestId,
      status,
      finished: true,
      resultMap: { eventData: frame as MESSAGE.EventData },
    })),
  };
}

describe("readChildSessionEventData", () => {
  it("控制帧不产出事件", () => {
    expect(readChildSessionEventData({ packageType: "heartbeat" })).toBeUndefined();
    expect(readChildSessionEventData({ packageType: "follow_idle" })).toBeUndefined();
    expect(readChildSessionEventData({ packageType: "follow_pending" })).toBeUndefined();
  });

  it("从 SSE 信封与回放帧里都能取到 eventData", () => {
    const data = eventData({
      messageType: "tool_thought",
      toolThought: "hi"
    });
    expect(readChildSessionEventData(frameOf(data))?.resultMap?.messageType).toBe(
      "tool_thought"
    );
    expect(readChildSessionEventData(data)?.resultMap?.messageType).toBe(
      "tool_thought"
    );
  });

  it("结构非法的负载返回 undefined 而不抛", () => {
    expect(readChildSessionEventData({ resultMap: { eventData: { foo: 1 } } })).toBeUndefined();
  });
});

describe("pickChildSessionRun / shouldFetchChildSessionReplay", () => {
  it("优先 latestRequestId，否则取最后一条 run", () => {
    const page = historyPage(
      [
        {
          requestId: "r1",
          status: "SUCCESS",
          hasReplay: true
        },
        {
          requestId: "r2",
          status: "SUCCESS",
          hasReplay: true
        },
      ],
      "r1"
    );
    expect(pickChildSessionRun(page)?.requestId).toBe("r1");
    expect(pickChildSessionRun(historyPage(page.runs))?.requestId).toBe("r2");
    expect(pickChildSessionRun(null)).toBeUndefined();
  });

  it("只有终态 run 才需要补拉账本回放", () => {
    expect(
      shouldFetchChildSessionReplay({
        requestId: "r1",
        status: "SUCCESS",
        hasReplay: true,
      })
    ).toBe(true);
    expect(
      shouldFetchChildSessionReplay({
        requestId: "r1",
        status: "RUNNING",
        hasReplay: true,
      })
    ).toBe(false);
    expect(shouldFetchChildSessionReplay(undefined)).toBe(false);
  });

  it("终态判定覆盖失败 / 停止 / 等待输入", () => {
    expect(isTerminalRunStatus("FAILED")).toBe(true);
    expect(isTerminalRunStatus("STOPPED")).toBe(true);
    expect(isTerminalRunStatus("WAITING_INPUT")).toBe(true);
    expect(isTerminalRunStatus("RUNNING")).toBe(false);
  });
});

describe("buildChildSessionChat", () => {
  it("终态走 replay：带出 query 与结论", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      replay: replayOf("r1", [
        eventData({
          messageType: "tool_thought",
          toolThought: "正在检索"
        }),
        eventData({
          messageType: "result",
          result: "子结论"
        }),
      ]),
    });

    expect(chat.sessionId).toBe("sub_1");
    expect(chat.requestId).toBe("r1");
    expect(chat.loading).toBe(false);
    expect(chat.conclusion?.result).toBe("子结论");
    expect(JSON.stringify(chat.tasks)).toContain("正在检索");
  });

  it("无 replay 时用实时帧，并按 eventSeq 去重", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      history: historyPage([{
        requestId: "r1",
        status: "RUNNING",
        hasReplay: true
      }]),
      frames: [
        {
          eventSeq: 1,
          ...frameOf(eventData({
            messageType: "agent_stream",
            result: "A"
          }))
        },
        {
          eventSeq: 2,
          ...frameOf(eventData({
            messageType: "agent_stream",
            result: "B"
          }))
        },
        {
          eventSeq: 2,
          ...frameOf(eventData({
            messageType: "agent_stream",
            result: "B"
          }))
        },
      ],
    });

    expect(chat.loading).toBe(true);
    expect(chat.conclusion?.result).toBe("AB");
  });

  it("replay 与实时帧合并：账本打底 + 实时补差，两者都进时间线", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      replay: replayOf("r1", [
        eventData({
          messageType: "tool_thought",
          toolThought: "早期工具轨迹",
        }),
      ]),
      frames: [
        {
          eventSeq: 1,
          ...frameOf(eventData({
            messageType: "agent_stream",
            result: "正在流式的最新正文"
          }))
        },
      ],
    });

    // 后端投影缓冲按落库边界清理后，实时帧只剩「账本还没写完的那一段」，
    // 已落库的早期轮次只在 replay 里 —— 只取其一都会丢内容。
    expect(JSON.stringify(chat.tasks)).toContain("早期工具轨迹");
    expect(chat.conclusion?.result).toBe("正在流式的最新正文");
  });

  it("子会话 result 无条件作为本会话结论（不做父子结论去重）", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      replay: replayOf("r1", [
        eventData({
          messageType: "result",
          result: "带父标记也要收下",
          parentToolUseId: "tool-parent",
        }),
      ]),
    });

    expect(chat.conclusion?.result).toBe("带父标记也要收下");
  });

  it("没有 result 帧时用 run 终态摘要兜底", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      replay: {
        requestId: "r1",
        status: "SUCCESS",
        finalSummaryText: "账本摘要",
        replayFrames: [],
      },
    });

    expect(chat.conclusion?.result).toBe("账本摘要");
  });

  it("history 未回来时用父卡文案兜底 query", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      fallbackQuery: "帮我查一下",
    });

    expect(chat.query).toBe("帮我查一下");
  });

  it("实时帧 finished 时收口 loading", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      history: historyPage([{
        requestId: "r1",
        status: "RUNNING",
        hasReplay: true
      }]),
      frames: [
        {
          eventSeq: 1,
          finished: true,
          ...frameOf(eventData({
            messageType: "result",
            result: "完成"
          })),
        },
      ],
    });

    expect(chat.loading).toBe(false);
    expect(chat.metrics?.status).toBe("SUCCESS");
  });

  it("回放帧带父会话 Agent 标签时，工具卡仍留在子会话时间线", () => {
    // 回归：账本回放会把派发者的 tool call id 打成 parentToolUseId，但那块 Agent 卡
    // 属于父会话，本子会话内不存在 → 派生层把工具塞进 pending 桶，完成后只剩终答。
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      replay: replayOf("r1", [
        replayToolEvent(
          "tool_call",
          {
            messageType: "tool_call",
            toolName: "workspace_write",
            toolCallId: "call_w1",
            parentToolUseId: "call_parent_agent",
            status: "success",
            input: { path: "a.md" },
          },
          "m-tool"
        ),
        replayToolEvent(
          "tool_result",
          {
            messageType: "tool_result",
            toolName: "workspace_write",
            toolCallId: "call_w1",
            parentToolUseId: "call_parent_agent",
            status: "success",
            result: "写入完成",
          },
          "m-tool"
        ),
        eventData(
          {
            messageType: "result",
            result: "子结论",
            parentToolUseId: "call_parent_agent",
          },
          { messageId: "m-result" }
        ),
      ]),
    });

    const tool = findTaskByToolCallId(chat, "call_w1");
    expect(tool).toBeDefined();
    expect(tool?.messageType).toBe("tool_result");
    expect(chat.conclusion?.result).toBe("子结论");
  });

  it("子 Agent 自己再派子 Agent 时保留嵌套（父卡确实在本会话内）", () => {
    const chat = buildChildSessionChat({
      childSessionId: "sub_1",
      replay: replayOf("r1", [
        replayToolEvent(
          "tool_call",
          {
            messageType: "tool_call",
            toolName: "Agent",
            toolCallId: "call_inner_agent",
            status: "success",
            input: { description: "内层子代理" },
          },
          "m-inner"
        ),
        replayToolEvent(
          "tool_call",
          {
            messageType: "tool_call",
            toolName: "workspace_read",
            toolCallId: "call_inner_tool",
            parentToolUseId: "call_inner_agent",
            status: "success",
            input: { path: "b.md" },
          },
          "m-inner-tool"
        ),
      ]),
    });

    const innerTool = findTaskByToolCallId(chat, "call_inner_tool");
    const parentCard = findTaskByToolCallId(chat, "call_inner_agent");
    expect(innerTool).toBeDefined();
    expect(parentCard).toBeDefined();
    // 内层工具必须挂在内层 Agent 卡下，而不是被剥成根时间线平铺
    expect(parentCard?.children?.some((child) => child === innerTool)).toBe(true);
  });

  it("父链标签指向会话外时只剥标签，不改动入参帧", () => {
    const raw = replayToolEvent(
      "tool_call",
      {
        messageType: "tool_call",
        toolName: "workspace_write",
        toolCallId: "call_w1",
        parentToolUseId: "call_parent_agent",
        status: "success",
      },
      "m-tool"
    );
    const snapshot = JSON.stringify(raw);

    buildChildSessionChat({
      childSessionId: "sub_1",
      replay: replayOf("r1", [raw]),
    });

    expect(JSON.stringify(raw)).toBe(snapshot);
  });
});

describe("buildChildSessionRounds", () => {
  it("单轮：有回放时直接用回放过程与结论", () => {
    const rounds = buildChildSessionRounds({
      childSessionId: "sub_1",
      history: historyPage([
        {
          requestId: "r1",
          status: "SUCCESS",
          queryPreview: "查一下",
          hasReplay: true
        },
      ]),
      replays: {
        r1: replayOf("r1", [
          eventData({
            messageType: "result",
            result: "子结论"
          }),
        ]),
      },
    });

    expect(rounds).toHaveLength(1);
    expect(rounds[0]?.requestId).toBe("r1");
    expect(rounds[0]?.conclusion?.result).toBe("子结论");
    expect(rounds[0]?.replayLoaded).toBe(true);
  });

  it("三轮：每个 run 一轮，缺回放的轮只产出提问 + 结论摘要的轻量壳", () => {
    const rounds = buildChildSessionRounds({
      childSessionId: "sub_1",
      history: historyPage([
        {
          requestId: "r1",
          status: "SUCCESS",
          queryPreview: "第一问",
          finalSummaryPreview: "第一答",
          hasReplay: true
        },
        {
          requestId: "r2",
          status: "SUCCESS",
          queryPreview: "第二问",
          finalSummaryPreview: "第二答",
          hasReplay: true
        },
        {
          requestId: "r3",
          status: "SUCCESS",
          queryPreview: "第三问",
          hasReplay: true
        },
      ]),
      replays: {
        r3: replayOf("r3", [
          eventData({
            messageType: "result",
            result: "第三答"
          }),
        ]),
      },
    });

    expect(rounds.map((round) => round.requestId)).toEqual(["r1", "r2", "r3"]);
    expect(rounds[0]?.query).toBe("第一问");
    expect(rounds[0]?.conclusion?.result).toBe("第一答");
    // 还没点开：可补回放但尚未加载，折叠条据此决定要不要发请求
    expect(rounds[0]?.replayLoaded).toBe(false);
    expect(rounds[0]?.replayAvailable).toBe(true);
    expect(JSON.stringify(rounds[0]?.tasks)).not.toContain("第三答");
    expect(rounds[2]?.replayLoaded).toBe(true);
    expect(rounds[2]?.conclusion?.result).toBe("第三答");
  });

  it("实时帧只归给流所属的 run，不串到历史轮上", () => {
    const rounds = buildChildSessionRounds({
      childSessionId: "sub_1",
      history: historyPage([
        {
          requestId: "r1",
          status: "SUCCESS",
          queryPreview: "旧问",
          hasReplay: true
        },
        {
          requestId: "r2",
          status: "RUNNING",
          queryPreview: "新问",
          hasReplay: true
        },
      ]),
      frames: [
        {
          reqId: "r2",
          eventSeq: 1,
          ...frameOf(
            eventData({
              messageType: "agent_stream",
              result: "最新正文"
            })
          ),
        },
      ],
    });

    expect(rounds[0]?.conclusion).toBeUndefined();
    expect(rounds[1]?.loading).toBe(true);
    expect(rounds[1]?.conclusion?.result).toBe("最新正文");
  });

  it("刚提交的 run 先补一条运行中的占位，落库后不重复", () => {
    const pendingRun = {
      requestId: "r2",
      query: "刚发的"
    };

    const beforeLanding = buildChildSessionRounds({
      childSessionId: "sub_1",
      history: historyPage([
        {
          requestId: "r1",
          status: "SUCCESS",
          queryPreview: "旧问",
          hasReplay: true
        },
      ]),
      pendingRun,
    });
    expect(beforeLanding).toHaveLength(2);
    expect(beforeLanding[1]?.requestId).toBe("r2");
    expect(beforeLanding[1]?.query).toBe("刚发的");
    expect(beforeLanding[1]?.loading).toBe(true);

    const afterLanding = buildChildSessionRounds({
      childSessionId: "sub_1",
      history: historyPage([
        {
          requestId: "r1",
          status: "SUCCESS",
          queryPreview: "旧问",
          hasReplay: true
        },
        {
          requestId: "r2",
          status: "RUNNING",
          queryPreview: "刚发的",
          hasReplay: true
        },
      ]),
      pendingRun,
    });
    // 同 requestId 的真实 run 顶替占位，不能出现两轮同样的提问
    expect(afterLanding).toHaveLength(2);
    expect(afterLanding[1]?.query).toBe("刚发的");
  });
});
