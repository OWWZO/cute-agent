import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import WorkspaceFileTree from "./WorkspaceFileTree";
import type { WorkspaceTreeNode } from "./workspaceFiles";

function fileNodes(count: number): WorkspaceTreeNode[] {
  return Array.from({ length: count }, (_, index) => ({
    kind: "file" as const,
    name: `file-${index}.txt`,
    path: `file-${index}.txt`,
    size: 1,
    file: {
      name: `file-${index}.txt`,
      type: "txt",
      url: `https://example.com/file-${index}.txt`,
    },
  })) as unknown as WorkspaceTreeNode[];
}

describe("WorkspaceFileTree 分页", () => {
  it("大目录默认只渲染一页并给出继续展开入口", () => {
    const html = renderToStaticMarkup(
      <WorkspaceFileTree nodes={fileNodes(60)} />
    );

    expect(html).toContain("file-0.txt");
    expect(html).toContain("file-49.txt");
    expect(html).not.toContain("file-50.txt");
    expect(html).toContain("显示更多（剩余 10）");
  });

  it("小目录不出现分页按钮", () => {
    const html = renderToStaticMarkup(
      <WorkspaceFileTree nodes={fileNodes(10)} />
    );
    expect(html).toContain("file-9.txt");
    expect(html).not.toContain("显示更多");
  });
});
