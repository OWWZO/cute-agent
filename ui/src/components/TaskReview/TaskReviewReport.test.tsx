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
  it("marks a tree-scoped review explicitly", () => {
    const value = { ...job(), scope: "tree" as const };
    const html = renderToStaticMarkup(createElement(TaskReviewReport, { job: value }));

    expect(html).toContain("已按整棵 Agent 树复盘");
  });

  it("hides empty assistant steps and does not duplicate the final reply", () => {
    const html = renderToStaticMarkup(createElement(TaskReviewReport, { job: job() }));

    expect(html).toContain("我先检查一下。");
    expect(html).toContain("navigate https://example.com");
    expect(html).toContain("navigation failed");
    expect(html).not.toContain("最终回复");
    expect(html).not.toContain("最终处理完成。");
    expect(html).not.toContain("（无记录文本）");
    expect(html).not.toContain(">OBSERVED<");
    expect(html).not.toContain("调用工具：browser");
  });

  it("renders failure attribution and the developer narrative report", () => {
    const value = job();
    value.diagnostics = {
      failureAttributions: [
        {
          attributionId: "attr-001",
          requestId: "req-1",
          runId: "run-1",
          toolCallId: "req-1:tool-1",
          step: 1,
          toolName: "browser",
          toolStatus: "FAILED",
          failureKind: "ARG_SELECTOR_NOT_FOUND",
          primaryLayer: "MODEL_ARGUMENT",
          contributingLayers: ["HARNESS_CONTRACT"],
          reason: "选择器不存在，且工具没有提示合法格式。",
          recovered: true,
          recoverySummary: "重新观察页面后恢复。",
          confidence: 0.88,
          source: "deterministic+judge",
          ruleIds: ["ARG_SELECTOR_NOT_FOUND"],
          evidenceRefs: ["tool:req-1:tool-1"],
          evidence: [],
        },
      ],
      diagnosticSummary: {
        totalFailures: 1,
        attributedFailures: 1,
        unattributedFailures: 0,
        layerCounts: { MODEL_ARGUMENT: 1 },
        recoveredCount: 1,
        unrecoveredCount: 0,
        parameterIssueCount: 1,
        diagnosisMismatchCount: 0,
      },
    };
    value.quality.dimensions = [
      {
        id: "artifact_quality",
        label: "产物质量",
        score: 0,
        level: "unknown",
        applicable: false,
        notApplicableReason: "本次没有文件产物。",
        reason: "",
        evidence: [],
      },
    ];
    value.narrative = {
      schemaVersion: "1.0",
      status: "verified",
      headline: "任务完成，但参数选择需要改进。",
      executiveSummary: ["执行成功，失败调用已恢复。"],
      taskUnderstanding: {
        goal: "验证浏览器动作",
        constraints: [],
        acceptanceCriteria: [],
      },
      executionNarrative: [],
      toolStory: [],
      failureStory: [],
      whatWorked: [],
      whatFailed: [],
      betterApproach: [],
      developerActions: [
        {
          actionId: "action-1",
          title: "修复 browser 选择器错误不可诊断的问题",
          targetLayer: "MODEL_ARGUMENT",
          priority: "P1",
          actionScope: "TOOL_IMPLEMENTATION",
          status: "ready",
          bugSignature:
            "MODEL_ARGUMENT|browser|ARG_SELECTOR_NOT_FOUND|selector",
          failureKind: "ARG_SELECTOR_NOT_FOUND",
          toolName: "browser",
          frequency: { count: 1, recovered: 1, unrecovered: 0 },
          problemDefinition: {
            observed: "selector 不存在。",
            expected: "返回可操作错误。",
            impact: "造成重复调用。",
          },
          evidenceRefs: ["tool:req-1:tool-1"],
          reproduction: {
            preconditions: ["页面已加载。"],
            steps: ["使用不存在的 selector。", "观察错误。"],
            expectedFailure: "selector not found",
          },
          rootCauseHypothesis: {
            summary: "错误缺少候选元素。",
            confidence: 0.88,
            alternatives: ["页面尚未渲染。"],
          },
          affectedAreas: [{ kind: "TOOL", name: "browser" }],
          fixPlan: [
            {
              order: 1,
              summary: "增加校验",
              detail: "返回结构化错误。",
            },
            {
              order: 2,
              summary: "增加恢复",
              detail: "允许重新观察。",
            },
          ],
          testPlan: {
            unit: ["非法 selector 返回错误码。"],
            integration: ["模型修正 selector 后成功。"],
            regression: ["有效 selector 不受影响。"],
          },
          acceptanceCriteria: ["错误包含 errorCode。"],
          target: "MODEL_ARGUMENT",
          action: "修复选择器错误",
          expectedEffect: "减少重复调用。",
          agentPrompt: "# 修复任务\n\n## 问题定义\n\n选择器不可诊断。",
        },
      ],
      verification: { verified: [], unverified: [] },
      limitations: [],
      markdown:
        "# Agent 任务复盘报告\n\n## 一句话结论\n\n参数选择有瑕疵，但已恢复。",
    };

    const html = renderToStaticMarkup(createElement(TaskReviewReport, { job: value }));

    expect(html).toContain("失败与责任归因");
    expect(html).toContain("选择器不存在");
    expect(html).toContain("综合复盘报告");
    expect(html).toContain("参数选择有瑕疵");
    expect(html).toContain("本次不适用");
    expect(html).toContain("修复 browser 选择器错误不可诊断的问题");
    expect(html).toContain("查看工具调用");
    expect(html).toContain("开发者行动项");
  });
});
