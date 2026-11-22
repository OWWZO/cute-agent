import { describe, expect, it } from "vitest";

import { buildSubmitPayload } from "./inputMode";

describe("inputMode", () => {
  it("深度研究模式不再透传输出格式，仅打开 isClusterMode", () => {
    expect(
      buildSubmitPayload({
        question: "帮我调研竞品",
        collaborationMode: "cluster",
        isDataAgent: false,
        uploadedFiles: [],
      })
    ).toMatchObject({
      collaborationMode: "cluster",
    });
    expect(
      buildSubmitPayload({
        question: "帮我调研竞品",
        collaborationMode: "cluster",
        isDataAgent: false,
        uploadedFiles: [],
      })
    ).not.toHaveProperty("outputStyle");
  });

  it("本轮计划开关只在显式打开时透传 forcePlanMode", () => {
    expect(
      buildSubmitPayload({
        question: "重新规划",
        collaborationMode: "cluster",
        isDataAgent: false,
        uploadedFiles: [],
        forcePlanMode: true,
      })
    ).toMatchObject({
      collaborationMode: "cluster",
      forcePlanMode: true,
    });
    expect(
      buildSubmitPayload({
        question: "继续执行",
        collaborationMode: "cluster",
        isDataAgent: false,
        uploadedFiles: [],
      })
    ).not.toHaveProperty("forcePlanMode");
  });

  it("标准任务不透传 outputStyle", () => {
    expect(
      buildSubmitPayload({
        question: "先帮我分析这个问题",
        collaborationMode: "normal",
        isDataAgent: false,
        uploadedFiles: [],
      })
    ).toMatchObject({
      collaborationMode: "normal",
    });
    expect(
      buildSubmitPayload({
        question: "先帮我分析这个问题",
        collaborationMode: "normal",
        isDataAgent: false,
        uploadedFiles: [],
      })
    ).not.toHaveProperty("outputStyle");
  });

  it("思考开启但未指定强度时默认使用高", () => {
    expect(
      buildSubmitPayload({
        question: "分析这个问题",
        collaborationMode: "normal",
        isDataAgent: false,
        uploadedFiles: [],
        thinking: true,
      })
    ).toMatchObject({
      thinking: true,
      thinkingEffort: "high",
    });
  });

  it("思考强度只有空白字符时同样回落到默认高", () => {
    expect(
      buildSubmitPayload({
        question: "分析这个问题",
        collaborationMode: "normal",
        isDataAgent: false,
        uploadedFiles: [],
        thinking: true,
        thinkingEffort: "  ",
      })
    ).toMatchObject({
      thinking: true,
      thinkingEffort: "high",
    });
  });
});
