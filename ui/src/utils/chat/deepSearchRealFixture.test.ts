import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import {
  canonicalizeDeepSearchStages,
  canonicalizeDeepSearchTask,
} from "./deepSearchCanonicalizer";

type RealFixtureStage = {
  stage: "extend" | "search" | "chapter_summary" | "report";
  isFinal?: boolean;
  searchFinish?: boolean;
  queries?: string[];
  results?: Array<{ query?: string; docs?: MESSAGE.Doc[] }>;
  chapterId?: string;
  chapterTitle?: string;
  answer?: string;
};

type RealFixture = {
  source: {
    requestId: string;
    sessionId: string;
    toolCallId: string;
  };
  timelineItems: Array<{ itemId: string; kind: string; parentItemId?: string | null }>;
  stages: RealFixtureStage[];
};

function readFixture(): RealFixture {
  const path = resolve(__dirname, "fixtures/deep-research-real-run.json");
  return JSON.parse(readFileSync(path, "utf8")) as RealFixture;
}

function taskFromStage(
  fixture: RealFixture,
  stage: RealFixtureStage,
  index: number,
): CHAT.Task {
  return {
    messageId: fixture.source.toolCallId,
    messageType: "deep_search",
    taskId: `task-${index}`,
    resultMap: {
      messageType: "deep_search",
      toolCallId: fixture.source.toolCallId,
      resultMap: {
        ...stage,
        messageType: stage.stage,
        searchResult: {
          query: stage.queries || [],
          docs: [],
        },
      },
    },
  } as unknown as CHAT.Task;
}

describe("真实 Deep Research run fixture", () => {
  it("fixture 来自真实 run，且保留单工具多阶段的 Ledger 事实", () => {
    const fixture = readFixture();

    expect(fixture.source.requestId).toBe("1795087313169-8722");
    expect(fixture.stages.map((stage) => stage.stage)).toEqual([
      "extend",
      "search",
      "chapter_summary",
      "chapter_summary",
      "report",
    ]);
    const deepSearchItems = fixture.timelineItems.filter(
      (item) => item.itemId === `tool:${fixture.source.toolCallId}`,
    );
    expect(deepSearchItems).toHaveLength(1);
  });

  it("真实 extend 阶段可稳定生成 8 张查询卡", () => {
    const fixture = readFixture();
    const cards = canonicalizeDeepSearchTask(
      taskFromStage(fixture, fixture.stages[0], 0),
      `deep_search:${fixture.source.toolCallId}:research`,
    );

    expect(cards).toHaveLength(8);
    expect(new Set(cards.map((card) => card.itemId)).size).toBe(8);
    expect(cards.map((card) => card.stage)).toEqual(
      Array.from({ length: 8 }, () => "extend"),
    );
  });

  it("真实 report 阶段只生成一张报告卡，正文来自同一工具事实", () => {
    const fixture = readFixture();
    const reportStage = fixture.stages[fixture.stages.length - 1];
    const cards = canonicalizeDeepSearchTask(
      taskFromStage(fixture, reportStage, fixture.stages.length - 1),
      `deep_search:${fixture.source.toolCallId}:report`,
    );

    expect(cards).toHaveLength(1);
    expect(cards[0].stage).toBe("report");
    expect(cards[0].task.resultMap?.answer).toContain("AI Agent");
  });

  it("历史 stages[] 能展开为完整查询、章节与报告卡", () => {
    const fixture = readFixture();
    const task = {
      messageId: fixture.source.toolCallId,
      messageType: "deep_search",
      taskId: "task-real-history",
      resultMap: {
        messageType: "deep_search",
        toolCallId: fixture.source.toolCallId,
        resultMap: {
          messageType: "deep_search",
          stages: fixture.stages,
        },
      },
    } as unknown as CHAT.Task;

    const cards = canonicalizeDeepSearchStages(
      task,
      `deep_search:${fixture.source.toolCallId}:research`,
    );

    expect(cards.filter((card) => card.stage === "chapter_summary")).toHaveLength(2);
    expect(cards.filter((card) => card.stage === "report")).toHaveLength(1);
    expect(new Set(cards.map((card) => card.itemId)).size).toBe(cards.length);
    expect(cards.length).toBeLessThan(17);
  });
});
