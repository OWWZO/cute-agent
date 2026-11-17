import { describe, expect, it } from "vitest";

import { normalizeSessionArtifactFiles } from "./taskArtifacts";
import { selectLatestTurnPreviewFile } from "./workspacePreview";

function file(
  name: string,
  requestId: string,
  artifactRole = "output",
  missing = false
): CHAT.TFile {
  return {
    name,
    url: `https://files.test/${name}`,
    type: name.split(".").pop() || "txt",
    size: 100,
    requestId,
    artifactRole,
    missing,
  };
}

describe("selectLatestTurnPreviewFile", () => {
  it("会话文件保留所属 run 与 artifact 角色", () => {
    const [normalized] = normalizeSessionArtifactFiles([
      {
        requestId: "run-2",
        artifactRole: "output",
        displayName: "report.html",
        relativePath: "reports/report.html",
        previewUrl: "https://files.test/report.html",
      },
    ], "session-1");

    expect(normalized).toMatchObject({
      requestId: "run-2",
      artifactRole: "output",
    });
  });

  it("只选最新一轮 run 的最后一个输出产物", () => {
    const files = [
      file("old.html", "run-1"),
      file("input.png", "run-2", "input"),
      file("research.md", "run-2"),
      file("final-report.html", "run-2"),
    ];

    expect(
      selectLatestTurnPreviewFile(files, ["run-2"])?.name
    ).toBe("final-report.html");
  });

  it("续跑 run 属于同一轮时仍从整轮里选择", () => {
    const files = [
      file("report.html", "run-2"),
      file("notes.md", "run-3"),
    ];

    expect(
      selectLatestTurnPreviewFile(files, ["run-2", "run-3"])?.name
    ).toBe("notes.md");
  });

  it("最新一轮没有产物时不回退展示旧轮文件", () => {
    expect(
      selectLatestTurnPreviewFile([file("old.html", "run-1")], ["run-2"])
    ).toBeUndefined();
  });

  it("最新一轮只有异步子 Agent 产物时仍自动展示", () => {
    const subAgentFile = file(
      "test-output.html",
      "sub:parent:child"
    );

    expect(
      selectLatestTurnPreviewFile([subAgentFile], ["run-root"])
    ).toBe(subAgentFile);
  });

  it("旧缓存没有 run 元数据时回退到最后一个可预览文件", () => {
    const legacy = [
      {
        ...file("legacy.html", ""),
        requestId: undefined,
        artifactRole: undefined,
      },
      {
        ...file("broken.html", "", "output", true),
        requestId: undefined,
      },
    ];

    expect(selectLatestTurnPreviewFile(legacy, ["run-2"])?.name).toBe(
      "legacy.html"
    );
  });
});
