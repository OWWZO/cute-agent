import { beforeEach, describe, expect, it } from "vitest";

import {
  buildDeepSearchShadowItems,
  canonicalizeDeepSearchTask,
  canonicalizeDeepSearchStages,
  inferDeepSearchStage,
} from "./deepSearchCanonicalizer";
import {
  getTimelineDiagnosticsSnapshot,
  resetTimelineDiagnostics,
} from "@/utils/timeline/timelineDiagnostics";

const realReportSummary = "真实报告正文";

function realRunTask(stageIndex: number): CHAT.Task {
  return {
    messageId: "chatcmpl-tool-b171719ed41f0a16",
    messageType: "deep_search",
    taskId: "task-deep-real",
    resultMap: {
      messageType: "deep_search",
      toolCallId: "chatcmpl-tool-b171719ed41f0a16",
      resultMap: {
        messageType: stageIndex === 0 ? "extend" : "report",
        ...(stageIndex === 0
          ? {
            queries: [
              "AI Agent 的概念与能力边界",
              "AI Agent 的产业应用与落地挑战",
              "AI Agent 定义 核心组件 规划 记忆 工具调用 2026",
            ],
            isFinal: false,
            searchFinish: false,
          }
          : {
            answer: realReportSummary,
            isFinal: true,
            searchFinish: true,
          }),
      },
    },
  } as unknown as CHAT.Task;
}

describe("Deep Research canonicalizer", () => {
  beforeEach(() => {
    resetTimelineDiagnostics();
  });

  it("真实 run 的 extend 阶段按 query 生成稳定卡", () => {
    const cards = canonicalizeDeepSearchTask(
      realRunTask(0),
      "deep_search:chatcmpl-tool-b171719ed41f0a16:research",
    );

    expect(cards).toHaveLength(3);
    expect(cards.map((card) => card.stage)).toEqual(["extend", "extend", "extend"]);
    expect(new Set(cards.map((card) => card.itemId)).size).toBe(3);
    expect(cards[0].itemId).toBe(
      "deep_search:chatcmpl-tool-b171719ed41f0a16:research",
    );
  });

  it("真实 run 的 report 阶段只生成一个报告卡，不继承 query 卡", () => {
    const cards = canonicalizeDeepSearchTask(
      realRunTask(1),
      "deep_search:chatcmpl-tool-b171719ed41f0a16:report",
    );

    expect(cards).toHaveLength(1);
    expect(cards[0].stage).toBe("report");
    expect(cards[0].itemId).toBe(
      "deep_search:chatcmpl-tool-b171719ed41f0a16:report",
    );
    expect(cards[0].task.resultMap?.answer).toBe(realReportSummary);
  });

  it("同一 query 在 extend/search/chapter_summary 之间保持身份", () => {
    const baseId = "deep_search:call-deep:research";
    const extend = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "msg-deep",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "extend",
          searchResult: { query: ["查询一"], docs: [] },
        },
      },
    } as unknown as CHAT.Task, baseId);
    const search = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "msg-deep",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "search",
          searchResult: {
            query: ["查询一"],
            docs: [[{ link: "https://example.com/a", title: "来源一" }]],
          },
        },
      },
    } as unknown as CHAT.Task, baseId);
    const chapter = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "msg-deep",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "chapter_summary",
          chapters: {
            C1: {
              chapterId: "C1",
              chapterTitle: "第一章",
              chapterOrder: 1,
              queries: ["查询一"],
              summary: "章节总结",
            },
          },
        },
      },
    } as unknown as CHAT.Task, baseId);

    expect(extend[0].itemId).toBe(search[0].itemId);
    expect(search[0].itemId).toBe(chapter[0].itemId);
  });

  it("占位卡升级为首个查询卡时 itemId 不变化", () => {
    const baseId = "deep_search:call-placeholder:research";
    const placeholder = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "call-placeholder",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "extend",
          searchResult: { query: [], docs: [] },
        },
      },
    } as unknown as CHAT.Task, baseId);
    const extended = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "call-placeholder",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "extend",
          searchResult: { query: ["查询一", "查询二"], docs: [] },
        },
      },
    } as unknown as CHAT.Task, baseId);

    expect(placeholder[0].itemId).toBe(baseId);
    expect(extended[0].itemId).toBe(placeholder[0].itemId);
  });

  it("历史 stages 与实时阶段使用相同的卡身份", () => {
    const researchBaseId = "deep_search:call-stages:research";
    const history = canonicalizeDeepSearchStages({
      messageType: "deep_search",
      messageId: "call-stages",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "deep_search",
          stages: [
            {
              stage: "extend",
              queries: ["查询一", "查询二"],
            },
            {
              stage: "search",
              results: [
                { query: "查询一", docs: [] },
                { query: "查询二", docs: [] },
              ],
            },
            {
              stage: "report",
              answer: "完整报告",
              isFinal: true,
            },
          ],
        },
      },
    } as unknown as CHAT.Task, researchBaseId);
    const liveExtend = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "call-stages",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "extend",
          searchResult: { query: ["查询一", "查询二"], docs: [] },
        },
      },
    } as unknown as CHAT.Task, researchBaseId);
    const liveReport = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "call-stages",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "report",
          answer: "完整报告",
          isFinal: true,
        },
      },
    } as unknown as CHAT.Task, "deep_search:call-stages:report");

    expect(history.map((card) => card.itemId)).toEqual([
      ...liveExtend.map((card) => card.itemId),
      liveReport[0].itemId,
    ]);
  });

  it("影子 timeline 使用稳定 itemId 和显式 upsert", () => {
    const items = buildDeepSearchShadowItems(realRunTask(0), "deep_search:real:research", {
      runId: "1795087313169-8722",
      sessionId: "session-1795087304478-6030",
      seq: 10,
    });

    expect(items.every((item) => item.operation === "upsert")).toBe(true);
    expect(items.map((item) => item.itemId)).toEqual(
      canonicalizeDeepSearchTask(realRunTask(0), "deep_search:real:research")
        .map((card) => card.itemId),
    );
    expect(items[0].parentItemId).toBe("tool:chatcmpl-tool-b171719ed41f0a16");
  });

  it("阶段推断只保留一个入口", () => {
    expect(inferDeepSearchStage({ messageType: "report", answer: "报告" })).toBe(
      "report",
    );
    expect(inferDeepSearchStage({ searchFinish: false })).toBe("extend");
    expect(inferDeepSearchStage({ searchResult: { query: [], docs: [] } })).toBe(
      "search",
    );
  });

  it("未知阶段仍可兜底渲染，同时在开发诊断中留痕", () => {
    const cards = canonicalizeDeepSearchTask({
      messageType: "deep_search",
      messageId: "call-unknown",
      resultMap: {
        messageType: "deep_search",
        resultMap: {
          messageType: "unexpected_stage",
          searchResult: { query: ["查询"], docs: [] },
        },
      },
    } as unknown as CHAT.Task, "deep_search:call-unknown:research");

    expect(cards).toHaveLength(1);
    expect(getTimelineDiagnosticsSnapshot().counters.unknown_stage).toBe(1);
  });
});
