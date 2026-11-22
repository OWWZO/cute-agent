import { describe, expect, it } from "vitest";
import { getTaskFiles } from "@/utils/taskArtifacts";
import {
  pickFeaturedDeliveryFiles,
  resolveTaskSummaryArtifactKeys,
  resolveTaskSummaryText,
  shouldShowWorkspaceFilesEntry,
} from "./contentHelpers";

const file = (
  name: string,
  extra?: Partial<CHAT.TFile>
): CHAT.TFile => {
  const path = extra?.relativePath || name;
  return {
    name,
    url: `https://example.com/preview/${path}`,
    type: name.split(".").pop() || "",
    size: 1,
    downloadUrl: `https://example.com/download/${path}`,
    ...extra,
  };
};

const conclusionTask = (
  summary: string,
  files: CHAT.TFile[]
): CHAT.Task =>
  ({
    result: summary,
    resultMap: {
      taskSummary: summary,
      fileList: files.map((item) => ({
        fileName: item.name,
        displayName: item.name,
        domainUrl: item.url,
        downloadUrl: item.downloadUrl,
        resourceKey: item.resourceKey,
        relativePath: item.relativePath,
      })),
      artifactRefs: files.map((item) => ({
        displayName: item.name,
        previewUrl: item.url,
        downloadUrl: item.downloadUrl,
        resourceKey: item.resourceKey,
        relativePath: item.relativePath,
      })),
    },
  }) as unknown as CHAT.Task;

describe("contentHelpers delivery files", () => {
  it("strips $$$ protocol from summary text and keeps artifact keys", () => {
    const task = conclusionTask(
      "已生成页面。$$$ notes-competitor-canvas.html",
      [file("notes-competitor-canvas.html")]
    );

    expect(resolveTaskSummaryText(task)).toBe("已生成页面。");
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([
      "notes-competitor-canvas.html",
    ]);
  });

  it("keeps only the $$$ named file of any type", () => {
    const json = file("report.json");
    const css = file("style.css", { relativePath: "css/style.css" });
    const html = file("index.html");
    const task = conclusionTask("请查看数据。$$$ report.json", [html, css, json]);

    expect(pickFeaturedDeliveryFiles(task)).toEqual([
      expect.objectContaining({
        name: "report.json",
      }),
    ]);
    expect(shouldShowWorkspaceFilesEntry(pickFeaturedDeliveryFiles(task), task)).toBe(
      true
    );
  });

  it("matches relative path and skips ambiguous basename", () => {
    const html = file("index.html", { relativePath: "site/index.html" });
    const cssA = file("style.css", { relativePath: "site/css/style.css" });
    const cssB = file("style.css", { relativePath: "theme/style.css" });
    const named = conclusionTask(
      "页面已生成。$$$ site/index.html、site/css/style.css",
      [html, cssA, cssB]
    );

    expect(pickFeaturedDeliveryFiles(named).map((item) => item.relativePath)).toEqual([
      "site/index.html",
      "site/css/style.css",
    ]);

    const ambiguous = conclusionTask("样式如下。$$$ style.css", [html, cssA, cssB]);
    expect(pickFeaturedDeliveryFiles(ambiguous)).toEqual([]);
  });

  it("does not feature individual files when $$$ is absent", () => {
    const json = file("report.json");
    const css = file("style.css");
    const task = conclusionTask("已完成。", [json, css]);

    expect(pickFeaturedDeliveryFiles(task)).toEqual([]);
    expect(shouldShowWorkspaceFilesEntry([], task, [json, css])).toBe(true);
  });

  it("keeps named files from stored artifactKeys after $$$ is stripped", () => {
    const json = file("report.json");
    const css = file("style.css");
    const task = conclusionTask("请查看数据。", [json, css]);
    (task.resultMap as Record<string, unknown>).artifactKeys = ["report.json"];

    expect(pickFeaturedDeliveryFiles(task).map((item) => item.name)).toEqual([
      "report.json",
    ]);
    expect(shouldShowWorkspaceFilesEntry(pickFeaturedDeliveryFiles(task), task)).toBe(
      true
    );
  });

  it("does not show the workspace entry when the named file is the only file", () => {
    const html = file("notes-competitor-canvas.html");
    const task = conclusionTask(
      "请查看页面。$$$ notes-competitor-canvas.html",
      [html]
    );

    expect(shouldShowWorkspaceFilesEntry(pickFeaturedDeliveryFiles(task), task, [html])).toBe(
      false
    );
  });

  it("matches $$$ names against session workspace files when conclusion has no fileList", () => {
    const html = file("index.html", { relativePath: "site/index.html" });
    const css = file("style.css", { relativePath: "site/css/style.css" });
    const task = conclusionTask("页面已生成。$$$ site/index.html", []);

    expect(pickFeaturedDeliveryFiles(task, [html, css]).map((item) => item.relativePath)).toEqual([
      "site/index.html",
    ]);
  });

  it("uses stored artifactKeys after live result strips $$$", () => {
    const html = file("index.html", { relativePath: "site/index.html" });
    const task = conclusionTask("页面已生成。", []);
    (task.resultMap as Record<string, unknown>).artifactKeys = ["site/index.html"];

    expect(pickFeaturedDeliveryFiles(task, [html]).map((item) => item.relativePath)).toEqual([
      "site/index.html",
    ]);
  });

  it("reads artifactKeys from the spread result task used by live SSE", () => {
    const html = file("simple-showcase.html");
    const task = {
      messageType: "result",
      result: "页面已生成。",
      taskSummary: "页面已生成。",
      artifactKeys: ["simple-showcase.html"],
    } as unknown as CHAT.Task;

    expect(resolveTaskSummaryArtifactKeys(task)).toEqual(["simple-showcase.html"]);
    expect(pickFeaturedDeliveryFiles(task, [html]).map((item) => item.name)).toEqual([
      "simple-showcase.html",
    ]);
  });

  it("still parses $$$ from result after taskSummary has been stripped", () => {
    const html = file("simple-showcase.html");
    const task = {
      result: "页面已生成。$$$ simple-showcase.html",
      resultMap: { taskSummary: "页面已生成。" },
    } as unknown as CHAT.Task;

    expect(resolveTaskSummaryText(task)).toBe("页面已生成。");
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual(["simple-showcase.html"]);
    expect(pickFeaturedDeliveryFiles(task, [html]).map((item) => item.name)).toEqual([
      "simple-showcase.html",
    ]);
  });

  it("matches simple-showcase.html from nested workspace file events", () => {
    const files = getTaskFiles({
      messageType: "file",
      resultMap: {
        messageType: "file",
        resultMap: {
          fileListOnly: true,
          fileInfo: [
            {
              fileName: "simple-showcase.html",
              relativePath: "simple-showcase.html",
              domainUrl: "https://example.com/preview/simple-showcase.html",
              ossUrl: "https://example.com/download/simple-showcase.html",
            },
          ],
        },
      },
    });
    const task = {
      result: "已生成。",
      taskSummary: "已生成。",
      artifactKeys: ["simple-showcase.html"],
    } as unknown as CHAT.Task;

    expect(files[0]?.relativePath).toBe("simple-showcase.html");
    expect(pickFeaturedDeliveryFiles(task, files).map((item) => item.name)).toEqual([
      "simple-showcase.html",
    ]);
  });

  it("does not synthesize a card when $$$ names a missing file", () => {
    const task = conclusionTask("请打开附件。$$$ simple-showcase.html", []);

    expect(resolveTaskSummaryArtifactKeys(task)).toEqual(["simple-showcase.html"]);
    expect(pickFeaturedDeliveryFiles(task)).toEqual([]);
  });

  it("ignores a closing $$$ delimiter instead of treating it as a file", () => {
    const html = file("index.html", { relativePath: "test-page/index.html" });
    const task = conclusionTask(
      "测试页已完成。$$$\ntest-page/index.html\n$$$",
      [html]
    );

    expect(resolveTaskSummaryArtifactKeys(task)).toEqual(["test-page/index.html"]);
    expect(pickFeaturedDeliveryFiles(task, [html]).map((item) => item.name)).toEqual([
      "index.html",
    ]);
  });

  it("does not synthesize a file card when only delimiters remain", () => {
    const html = file("report.html");
    const task = conclusionTask("页面已生成。$$$\n$$$", [html]);

    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([]);
    expect(pickFeaturedDeliveryFiles(task, [html])).toEqual([]);
  });

  it("keeps inline $$$ prose and uses the trailing artifact section", () => {
    const html = file("index.html", { relativePath: "test-page/index.html" });
    const readme = file("README.md", { relativePath: "test-page/README.md" });
    const summary = [
      "你之前看不到 `screens/` 的原因：上一轮会话结束时工作区被重置。",
      "我上一条 `$$$` 里写的 `test-page/screens/*.png` 在当时已失效。",
      "",
      "**当前真实结构**：",
      "test-page/",
      "├── index.html          33 KB   交付物，单文件自包含",
      "",
      "$$$",
      "test-page/index.html",
      "test-page/README.md",
    ].join("\n");
    const task = conclusionTask(summary, [html, readme]);

    expect(resolveTaskSummaryText(task)).toContain(
      "我上一条 `$$$` 里写的 `test-page/screens/*.png` 在当时已失效"
    );
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([
      "test-page/index.html",
      "test-page/README.md",
    ]);
    expect(pickFeaturedDeliveryFiles(task).map((item) => item.name)).toEqual([
      "index.html",
      "README.md",
    ]);
  });

  it("does not treat a prose-only $$$ mention as an artifact section", () => {
    const summary = [
      "我上一条 `$$$` 里写的 `test-page/screens/*.png` 在当时已失效，",
      "这是我未在交付前重新核对路径造成的，抱歉。",
      "",
      "**当前真实结构**：",
      "test-page/",
      "├── index.html          33 KB   交付物，单文件自包含",
      "└── screens/",
      "    └── desktop.png     271 KB",
    ].join("\n");
    const task = conclusionTask(summary, []);

    expect(resolveTaskSummaryText(task)).toBe(summary);
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([]);
    expect(pickFeaturedDeliveryFiles(task)).toEqual([]);
  });

  it("does not strip an inline delimiter at the end of prose", () => {
    const summary = "文件清单分隔符写作 $$$";
    const task = conclusionTask(summary, []);

    expect(resolveTaskSummaryText(task)).toBe(summary);
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([]);
  });

  it("strips list markers and trailing punctuation from $$$ keys", () => {
    const html = file("simple-showcase.html");
    const task = conclusionTask(
      "请打开。$$$\n- simple-showcase.html。",
      []
    );

    expect(resolveTaskSummaryArtifactKeys(task)).toEqual(["simple-showcase.html"]);
    expect(pickFeaturedDeliveryFiles(task, [html]).map((item) => item.name)).toEqual([
      "simple-showcase.html",
    ]);
  });

  it("accepts a double-dollar delimiter at line start", () => {
    const pdf = file(
      "11.19.26-ECRA-Information-Disclosure-of-BIS-License-Decisions.pdf",
      {
        relativePath:
          "sources/11.19.26-ECRA-Information-Disclosure-of-BIS-License-Decisions.pdf",
      }
    );
    const task = conclusionTask(
      "PDF 已读取。\n$$ sources/11.19.26-ECRA-Information-Disclosure-of-BIS-License-Decisions.pdf",
      [pdf]
    );

    expect(resolveTaskSummaryText(task)).toBe("PDF 已读取。");
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([
      "sources/11.19.26-ECRA-Information-Disclosure-of-BIS-License-Decisions.pdf",
    ]);
    expect(pickFeaturedDeliveryFiles(task).map((item) => item.name)).toEqual([
      "11.19.26-ECRA-Information-Disclosure-of-BIS-License-Decisions.pdf",
    ]);
  });

  it("does not treat inline double-dollar math as an artifact section", () => {
    const summary = "公式 $$x^2$$ 不代表文件清单";
    const task = conclusionTask(summary, []);

    expect(resolveTaskSummaryText(task)).toBe(summary);
    expect(resolveTaskSummaryArtifactKeys(task)).toEqual([]);
  });

});
