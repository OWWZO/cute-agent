import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { AgentStepTimeline, isDuplicateOfConclusion } from "./AgentStepTimeline";

describe("AgentStepTimeline", () => {
  it("纯正文更新复用任务模型，但保留最新 chat 给步骤行", () => {
    const chat = {
      sessionId: "s1",
      requestId: "r1",
      query: "读取文件",
      loading: true,
      startedAt: "1714041600000",
      files: [],
      tasks: [[
        {
          id: "container-1",
          children: [{
            id: "tool-1",
            messageId: "tool-1",
            messageType: "tool_call",
            messageTime: "1714041601000",
            resultMap: {
              toolName: "Read",
              input: { path: "src/main.ts" },
              isFinal: false,
            },
          }],
        }],
      ],
      multiAgent: { tasks: [] },
      conclusion: undefined,
    } as unknown as CHAT.ChatItem;
    const latestChat = {
      ...chat,
      response: "不断增长的正文",
      tasks: chat.tasks,
    } as CHAT.ChatItem;
    const onOpenToolDiff = vi.fn();
    const props = {
      chat,
      isPlanSolveMessage: false,
      changeActiveChat: vi.fn(),
      onOpenToolDiff,
    };

    const first = renderToStaticMarkup(<AgentStepTimeline {...props} />);
    const second = renderToStaticMarkup(
      <AgentStepTimeline {...props} chat={latestChat} />
    );

    expect(first).toContain("src/main.ts");
    expect(second).toContain("src/main.ts");
  });

  it("把纯代码过程回复识别为与最终结论重复", () => {
    const code = '```python\nprint("你好，世界！")\n```';

    expect(
      isDuplicateOfConclusion(
        {
          type: "assistant_reply",
          text: code,
        } as never,
        code
      )
    ).toBe(true);
  });

  it("reasoning 条目完成后，即使整轮仍在运行也自动收起", () => {
    const chat = {
      sessionId: "s1",
      requestId: "r1",
      query: "分析问题",
      loading: true,
      startedAt: "1714041600000",
      files: [],
      tasks: [[
        {
          id: "container-1",
          children: [{
            id: "reasoning-1",
            messageId: "reasoning-1",
            messageType: "llm_reasoning",
            messageTime: "1714041601000",
            toolThought: "这段思考已经结束",
            finish: true,
            isFinal: true,
            resultMap: { isFinal: true },
          }],
        },
      ]],
      multiAgent: { tasks: [] },
    } as unknown as CHAT.ChatItem;

    const html = renderToStaticMarkup(
      <AgentStepTimeline
        chat={chat}
        isPlanSolveMessage={false}
        changeActiveChat={vi.fn()}
      />
    );

    expect(html).toContain('data-testid="process-thinking"');
    expect(html).toContain('data-open="false"');
    expect(html).not.toContain("kimi-think-live");
  });

  it("顶部的计划思考完成后，即使整轮仍在运行也自动收起", () => {
    const chat = {
      sessionId: "s1",
      requestId: "r1",
      query: "制定计划",
      loading: true,
      startedAt: "1714041600000",
      files: [],
      tasks: [],
      multiAgent: { tasks: [] },
    } as unknown as CHAT.ChatItem;

    const html = renderToStaticMarkup(
      <AgentStepTimeline
        chat={chat}
        isPlanSolveMessage
        thoughtText="计划思考已经结束"
        thoughtStreaming={false}
        changeActiveChat={vi.fn()}
      />
    );

    expect(html).toContain('data-open="false"');
    expect(html).not.toContain("kimi-think-live");
  });
});
