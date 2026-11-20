import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import {
  canonicalizeDeepSearchStages,
  canonicalizeDeepSearchTask,
} from "./deepSearchCanonicalizer";
import { processTaskForRender } from "./renderTasks";

type RealFixture = {
  source: { toolCallId: string };
  stages: Array<Record<string, unknown>>;
};

function readFixture(): RealFixture {
  return JSON.parse(readFileSync(
    resolve(__dirname, "fixtures/deep-research-real-run.json"),
    "utf8",
  )) as RealFixture;
}

function historyTask(fixture: RealFixture): CHAT.Task {
  return {
    messageId: fixture.source.toolCallId,
    messageType: "deep_search",
    taskId: "task-shadow-history",
    resultMap: {
      messageType: "deep_search",
      toolCallId: fixture.source.toolCallId,
      resultMap: {
        messageType: "deep_search",
        stages: fixture.stages,
      },
    },
  } as unknown as CHAT.Task;
}

describe("Deep Research projection consistency", () => {
  it("真实历史 stages[] 经 canonicalizer 与任务投影后的数量和身份一致", () => {
    const fixture = readFixture();
    const task = historyTask(fixture);
    const baseId = `deep_search:${fixture.source.toolCallId}:research`;
    const legacy = processTaskForRender(task, baseId);
    const canonical = canonicalizeDeepSearchStages(task, baseId);

    expect(canonical.map((card) => card.itemId)).toEqual(
      legacy.map((card) => card.id),
    );
  });

  it("实时单阶段帧经两条投影入口后的卡片身份一致", () => {
    const fixture = readFixture();
    const baseId = `deep_search:${fixture.source.toolCallId}:research`;
    const task = {
      messageId: fixture.source.toolCallId,
      messageType: "deep_search",
      taskId: "task-shadow-live",
      resultMap: {
        messageType: "deep_search",
        toolCallId: fixture.source.toolCallId,
        resultMap: {
          messageType: "chapter_summary",
          searchResult: {
            query: ["AI Agent 定义 核心组件 规划 记忆 工具调用 2026"],
            docs: [[]],
          },
          chapters: {
            C1: {
              chapterId: "C1",
              chapterTitle: "AI Agent 的定义",
              chapterOrder: 1,
              queries: ["AI Agent 定义 核心组件 规划 记忆 工具调用 2026"],
              summary: "章节总结",
            },
          },
        },
      },
    } as unknown as CHAT.Task;

    const legacy = processTaskForRender(task, baseId);
    const canonical = canonicalizeDeepSearchTask(task, baseId);

    expect(canonical.map((card) => card.itemId)).toEqual(
      legacy.map((card) => card.id),
    );
  });
});
