import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { TaskReviewReport } from "./TaskReviewReport";
import type { TaskReviewJob } from "@/services/taskReview";

function job(): TaskReviewJob {
  return {
    schemaVersion: "1.0",
    jobId: "review-1",
    status: "completed",
    mode: "standard",
    includeLlmJudge: false,
    sessionId: "session-1",
    requestIds: ["req-1"],
    title: "测试任务",
    model: "group/group",
    createdAt: "2026-11-16T00:00:00Z",
    updatedAt: "2026-11-16T00:00:01Z",
    progress: [],
    fast: {
      schemaVersion: "1.0",
      subject: { requestIds: ["req-1"], runCount: 1 },
      execution: {
        status: "SUCCESS",
        durationMs: 1200,
        totalTokens: 120,
        toolCount: 1,
        failedToolCount: 1,
        artifactCount: 0,
      },
      completion: {
        conclusion: "partial",
        basis: "execution",
        advisory: true,
        summary: "执行完成，但有一次工具失败。",
      },
      findings: [],
      metrics: { repeatedCallExcess: 0, riskScore: 0 },
      runs: [
        {
          requestId: "req-1",
          status: "SUCCESS",
          finalText: "最终处理完成。",
          execution: { status: "SUCCESS", durationMs: 1200, totalTokens: 120 },
          timeline: {
            steps: [
              {
                id: "step-1",
                ordinal: 1,
                content: "",
                summary: "调用工具：browser",
                status: "FAILED",
                rawStatus: "OBSERVED",
                kind: "tool",
                visible: false,
                toolCalls: [
                  {
                    toolCallId: "tool-1",
                    toolName: "browser",
                    argumentBrief: "navigate https://example.com",
                    argumentsPreview: '{"args":["navigate","https://example.com"]}',
                    status: "FAILED",
                    failed: true,
                    errorMsg: "navigation failed",
                    resultPreview: "navigation failed",
                  },
                ],
              },
              {
                id: "step-2",
                ordinal: 2,
                content: "我先检查一下。",
                summary: "我先检查一下。",
                status: "SUCCESS",
                rawStatus: "SUCCESS",
                kind: "model",
                visible: true,
              },
            ],
            tools: [
              {
                id: "tool-1",
                toolCallId: "tool-1",
                toolName: "browser",
                status: "FAILED",
                failed: true,
                errorMsg: "navigation failed",
                durationMs: 400,
                stepNo: 1,
                dispatchIndex: 0,
                argumentBrief: "navigate https://example.com",
                argumentsPreview: '{"args":["navigate","https://example.com"]}',
                resultPreview: "navigation failed",
              },
            ],
          },
        },
      ],
      artifacts: [],
      limitations: [],
    },
    quality: {
      status: "unverified",
      summary: "本次只执行快速体检。",
      dimensions: [],
      findings: [],
      suggestions: [],
      limitations: [],
      advisory: true,
    },
  };
}

describe("TaskReviewReport timeline", () => {
  it("hides assistant steps without text but keeps tools and the final reply", () => {
    const html = renderToStaticMarkup(createElement(TaskReviewReport, { job: job() }));

    expect(html).toContain("我先检查一下。");
    expect(html).toContain("navigate https://example.com");
    expect(html).toContain("navigation failed");
    expect(html).toContain("最终回复");
    expect(html).toContain("最终处理完成。");
    expect(html).not.toContain("（无记录文本）");
    expect(html).not.toContain(">OBSERVED<");
    expect(html).not.toContain("调用工具：browser");
  });
});
