import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import ConclusionSection from "./ConclusionSection";

const htmlFile: CHAT.TFile = {
  name: "report.html",
  url: "https://example.com/preview/report.html",
  type: "html",
  size: 1,
  resourceKey: "report.html",
};

const chatWithLoading = (loading: boolean) =>
  ({
    sessionId: "session-1",
    requestId: "req-1",
    loading,
    conclusion: {
      messageType: "agent_stream",
      result: "页面已生成。",
      resultMap: { taskSummary: "页面已生成。" },
    },
  }) as unknown as CHAT.ChatItem;

const render = (loading: boolean) =>
  renderToStaticMarkup(
    <ConclusionSection
      chat={chatWithLoading(loading)}
      sessionArtifactFiles={[htmlFile]}
      onOpenWorkspaceFiles={() => undefined}
    />
  );

describe("ConclusionSection workspace files entry", () => {
  it("终答仍在流式输出时不展示全部文件入口", () => {
    expect(render(true)).not.toContain("全部文件");
  });

  it("终答收口后才展示全部文件入口", () => {
    expect(render(false)).toContain("全部文件");
  });
});
