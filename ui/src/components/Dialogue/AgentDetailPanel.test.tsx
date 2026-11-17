import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AgentDetailPanel } from "./AgentDetailPanel";

const childSessionTool = {
  id: "agent-1",
  messageId: "agent-1",
  messageType: "tool_call",
  messageTime: "1714041600000",
  resultMap: {
    toolName: "Agent",
    childSessionId: "sub_agent-1",
    input: {
      prompt: "research",
      subagent_type: "Explore",
    },
  },
} as unknown as CHAT.Task;

const childSessionChat = {
  sessionId: "s1",
  requestId: "r1",
} as CHAT.ChatItem;

describe("AgentDetailPanel shared dialogue renderer", () => {
  it("does not synthesize a second renderer when childSessionId is missing", () => {
    const html = renderToStaticMarkup(
      <AgentDetailPanel
        tool={
          {
            id: "agent-1",
            messageId: "agent-1",
            messageType: "tool_call",
            messageTime: "1714041600000",
            resultMap: {
              toolName: "Agent",
              input: {
                prompt: "research",
                subagent_type: "Explore",
              },
            },
            children: [
              {
                id: "child-1",
                messageId: "child-1",
                messageType: "deep_search",
                messageTime: "1714041601000",
                resultMap: {
                  messageType: "search",
                  searchResult: {
                    query: ["query"],
                    docs: [
                      [
                        {
                          link: "https://example.com",
                          title: "source",
                        },
                      ],
                    ],
                  },
                },
              },
            ],
          } as unknown as CHAT.Task
        }
        chat={
          {
            sessionId: "s1",
            requestId: "r1",
          } as CHAT.ChatItem
        }
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("没有可用的独立会话轨迹");
    expect(html).not.toContain("搜索完成");
    expect(html).not.toContain("query");
  });
});

describe("AgentDetailPanel real child session (flag on)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("flag 开启且父卡带 childSessionId 时显示加载骨架，不塞合成投影", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");

    const html = renderToStaticMarkup(
      <Panel tool={childSessionTool} chat={childSessionChat} onClose={vi.fn()} />
    );

    // SSR 不跑 effect → 订阅未启动 → 只应看到骨架，不应出现合成投影里的子工具内容
    expect(html).toContain("加载子会话");
    expect(html).not.toContain("搜索完成");
  });

  it("flag 开启但没有 childSessionId 时不回退合成投影", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");

    const html = renderToStaticMarkup(
      <Panel
        tool={
          {
            ...childSessionTool,
            resultMap: {
              toolName: "Agent",
              input: {
                prompt: "research",
                subagent_type: "Explore"
              },
            },
            children: [
              {
                id: "child-1",
                messageId: "child-1",
                messageType: "deep_search",
                messageTime: "1714041601000",
                resultMap: {
                  messageType: "search",
                  searchResult: {
                    query: ["query"],
                    docs: []
                  },
                },
              },
            ],
          } as unknown as CHAT.Task
        }
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).not.toContain("加载子会话");
    expect(html).toContain("没有可用的独立会话轨迹");
    expect(html).not.toContain("query");
  });

  it("flag 关闭时只显示关闭状态，不重新启用合成投影", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "0");
    vi.resetModules();
    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel tool={childSessionTool} chat={childSessionChat} onClose={vi.fn()} />
    );

    expect(html).toContain("子 Agent 详情已关闭");
    expect(html).not.toContain("加载子会话");
    expect(html).not.toContain("query");
  });

  it("reconnectExhausted 时展示重连失败提示，而不是静默停在原地", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();

    // SSR 不跑 effect，hook 的返回值拿不到，直接给订阅钩子喂熔断状态
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "",
      history: null,
      runs: {},
      activeRequestId: "",
      source: "live",
      reconnectAttempts: 20,
      reconnectExhausted: true,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel tool={childSessionTool} chat={childSessionChat} onClose={vi.fn()} />
    );

    expect(html).toContain("重连失败");
    // 提示必须说明「保留最后一次进度」与可操作的下一步，不能只说断开了
    expect(html).toContain("最后一次收到的进度");
    expect(html).toContain("刷新");
  });
});
