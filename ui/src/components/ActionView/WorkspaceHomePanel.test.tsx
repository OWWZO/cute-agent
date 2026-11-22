import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import WorkspaceHomePanel from "./WorkspaceHomePanel";
import type { WorkspaceFileItem } from "./workspaceFiles";

function file(name: string, relativePath: string): WorkspaceFileItem {
  return {
    name,
    relativePath,
    type: name.split(".").pop() || "",
    url: `https://files.test/${relativePath}`,
    task: {},
  } as WorkspaceFileItem;
}

describe("WorkspaceHomePanel", () => {
  it("空工作区展示明确的空状态，而不是空白面板", () => {
    const html = renderToStaticMarkup(
      <WorkspaceHomePanel files={[]} onOpenFile={() => undefined} />
    );

    expect(html).toContain("还没有工作区内容");
    expect(html).toContain("对话生成的文件和预览会出现在这里");
  });

  it("有文件时按目录展示当前会话文件", () => {
    const html = renderToStaticMarkup(
      <WorkspaceHomePanel
        files={[file("report.md", "out/report.md")]}
        onOpenFile={() => undefined}
      />
    );

    expect(html).toContain("文件");
    expect(html).toContain("out");
    expect(html).toContain("report.md");
    expect(html).not.toContain("还没有工作区内容");
  });
});
