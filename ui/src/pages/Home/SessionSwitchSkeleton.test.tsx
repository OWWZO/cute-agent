import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { SessionSwitchSkeleton } from "./SessionSwitchSkeleton";

describe("SessionSwitchSkeleton", () => {
  it("用 role=status 暴露加载态，避免屏幕阅读器读到空内容", () => {
    const html = renderToStaticMarkup(<SessionSwitchSkeleton />);

    expect(html).toContain('role="status"');
    expect(html).toContain('aria-busy="true"');
    expect(html).toContain('aria-label="正在加载会话"');
  });

  it("渲染若干 turn 占位，而不是空壳", () => {
    const html = renderToStaticMarkup(<SessionSwitchSkeleton />);

    // 每个 turn = 1 个提问条 + 3 行结论 + 过程卡
    expect(html.match(/data-slot="skeleton"/g)?.length ?? 0).toBeGreaterThan(4);
  });

  it("容器宽度与真实对话区一致，避免骨架与内容跳动", () => {
    const html = renderToStaticMarkup(<SessionSwitchSkeleton />);

    expect(html).toContain("max-w-[860px]");
  });
});
