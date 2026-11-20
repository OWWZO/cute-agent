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
    childRequestId: "child-req-1",
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
  it("running 且 childSessionId 尚未到达时显示建立中，不显示无轨迹空态", () => {
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

    expect(html).toContain("正在建立子会话");
    expect(html).not.toContain("没有可用的独立会话轨迹");
    expect(html).not.toContain("搜索完成");
    expect(html).not.toContain("query");
  });

  it("终态且确实没有 childSessionId 时才显示无轨迹空态", () => {
    const html = renderToStaticMarkup(
      <AgentDetailPanel
        tool={
          {
            ...childSessionTool,
            resultMap: {
              toolName: "Agent",
              status: "success",
              isFinal: true,
              input: {
                prompt: "research",
                subagent_type: "Explore"
              },
            },
          } as unknown as CHAT.Task
        }
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("没有可用的独立会话轨迹");
    expect(html).not.toContain("正在建立子会话");
  });
});

describe("AgentDetailPanel real child session (flag on)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
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

  it("父卡 childRequestId 会直接传给子会话订阅", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    const timelineSpy = vi
      .spyOn(sessionModule, "useSessionTimeline")
      .mockReturnValue({
        ready: false,
        loading: true,
        error: "",
        history: null,
        runs: {},
        activeRequestId: "",
        source: "idle",
        reconnectAttempts: 0,
        reconnectExhausted: false,
        reload: vi.fn(),
        loadRun: vi.fn(),
        observeRun: vi.fn(),
      });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    renderToStaticMarkup(
      <Panel tool={childSessionTool} chat={childSessionChat} onClose={vi.fn()} />
    );

    expect(timelineSpy).toHaveBeenCalledWith(
      "sub_agent-1",
      true,
      "child-req-1"
    );
  });

  it("历史已就绪但本轮 run 尚未到达时显示读取中，不显示无轨迹空态", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "",
      history: {
        sessionId: "sub_agent-1",
        title: "子会话",
        runs: [],
      } as never,
      runs: {},
      activeRequestId: "child-req-1",
      source: "history",
      reconnectAttempts: 0,
      reconnectExhausted: false,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel tool={childSessionTool} chat={childSessionChat} onClose={vi.fn()} />
    );

    expect(html).toContain("正在读取子会话轨迹");
    expect(html).not.toContain("没有可用的独立会话轨迹");
  });

  it("父卡终态但 child run 尚未被历史索引发现时先等待，不立即显示无轨迹", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "",
      history: {
        sessionId: "sub_agent-1",
        title: "子会话",
        runs: [],
      } as never,
      runs: {},
      activeRequestId: "child-req-1",
      source: "history",
      reconnectAttempts: 0,
      reconnectExhausted: false,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel
        tool={{
          ...childSessionTool,
          resultMap: {
            ...childSessionTool.resultMap,
            status: "success",
            isFinal: true,
          },
        } as unknown as CHAT.Task}
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("正在读取子会话轨迹");
    expect(html).not.toContain("没有可用的独立会话轨迹");
  });

  it("只有 childSessionId、没有 childRequestId 时也给历史索引一个发现窗口", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "",
      history: {
        sessionId: "sub_agent-1",
        title: "子会话",
        runs: [],
      } as never,
      runs: {},
      activeRequestId: "",
      source: "history",
      reconnectAttempts: 0,
      reconnectExhausted: false,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel
        tool={{
          ...childSessionTool,
          resultMap: {
            ...childSessionTool.resultMap,
            childRequestId: undefined,
            status: "success",
            isFinal: true,
          },
        } as unknown as CHAT.Task}
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("正在读取子会话轨迹");
    expect(html).not.toContain("没有可用的独立会话轨迹");
  });

  it("终态子 run 已确认且没有条目时才显示无轨迹", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "",
      history: {
        sessionId: "sub_agent-1",
        title: "子会话",
        runs: [{
          requestId: "child-req-1",
          status: "SUCCESS",
          hasReplay: false,
        }],
      } as never,
      runs: {},
      activeRequestId: "child-req-1",
      source: "history",
      reconnectAttempts: 0,
      reconnectExhausted: false,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel
        tool={{
          ...childSessionTool,
          resultMap: {
            ...childSessionTool.resultMap,
            status: "success",
            isFinal: true,
          },
        } as unknown as CHAT.Task}
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("没有可用的独立会话轨迹");
    expect(html).not.toContain("正在读取子会话轨迹");
  });

  it("运行中的子会话读取失败时仍显示读取中，不显示无轨迹空态", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "session not found",
      history: {
        sessionId: "sub_agent-1",
        title: "子会话",
        runs: [],
      } as never,
      runs: {},
      activeRequestId: "child-req-1",
      source: "history",
      reconnectAttempts: 0,
      reconnectExhausted: false,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel
        tool={
          {
            ...childSessionTool,
            resultMap: {
              ...childSessionTool.resultMap,
              status: "running",
            },
          } as unknown as CHAT.Task
        }
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("正在读取子会话轨迹");
    expect(html).not.toContain("没有可用的独立会话轨迹");
    expect(html).not.toContain("子会话读取失败");
  });

  it("终态子会话读取失败时显示错误，不伪装成无轨迹", async () => {
    vi.stubEnv("VITE_SUBAGENT_CHILD_SESSION", "1");
    vi.resetModules();
    const sessionModule = await import("@/hooks/useSessionTimeline");
    vi.spyOn(sessionModule, "useSessionTimeline").mockReturnValue({
      ready: true,
      loading: false,
      error: "session not found",
      history: null,
      runs: {},
      activeRequestId: "child-req-1",
      source: "history",
      reconnectAttempts: 0,
      reconnectExhausted: false,
      reload: vi.fn(),
      loadRun: vi.fn(),
      observeRun: vi.fn(),
    });

    const { AgentDetailPanel: Panel } = await import("./AgentDetailPanel");
    const html = renderToStaticMarkup(
      <Panel
        tool={{
          ...childSessionTool,
          resultMap: {
            ...childSessionTool.resultMap,
            status: "success",
            isFinal: true,
          },
        } as unknown as CHAT.Task}
        chat={childSessionChat}
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("子会话读取失败");
    expect(html).not.toContain("没有可用的独立会话轨迹");
  });

  it("flag 开启但运行中尚未拿到 childSessionId 时不回退合成投影", async () => {
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
    expect(html).toContain("正在建立子会话");
    expect(html).not.toContain("没有可用的独立会话轨迹");
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
