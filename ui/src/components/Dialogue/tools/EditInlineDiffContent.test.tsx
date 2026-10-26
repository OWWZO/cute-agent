import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { EditInlineDiffContent } from "./EditInlineDiffContent";

const PATCH = ["--- a/demo.ts", "+++ b/demo.ts", "@@", "+const a = 1;", "+const b = 2;"].join(
  "\n"
);

/**
 * 首帧（effect 尚未执行）一定是纯文本 hunk —— 高亮是异步补的。
 * 这组用例锁的是「展开瞬间不能同步跑 shiki」这个前提。
 */
describe("EditInlineDiffContent", () => {
  it("首帧渲染纯文本 hunk，不注入 shiki token span", () => {
    const html = renderToStaticMarkup(
      <EditInlineDiffContent patch={PATCH} path="demo.ts" />
    );

    expect(html).toContain("edit-inline-diff");
    expect(html).toContain("const a = 1;");
    // 没有 token 时不会出现带 inline color 的 span
    expect(html).not.toContain("style=\"color:");
  });

  it("不把 unified diff 的 +/- 协议 marker 渲染成正文", () => {
    const html = renderToStaticMarkup(
      <EditInlineDiffContent patch={PATCH} path="demo.ts" />
    );

    expect(html).not.toContain(">+const a = 1;<");
  });

  /**
   * 回归：流式期间必须跳过 shiki。
   * 30KB 的 patch 单次 tokenize 实测 ~116ms，而流式时 patch 每帧都在变 ——
   * 不门禁就是每帧一次全量 TextMate 高亮，主线程被直接打满。
   * 这里断言的是渲染结果与 streaming 无关（首帧本来就无高亮），
   * 真正的门禁在 effect 里，由 `shouldTokenizeCode` 的单元测试覆盖。
   */
  it("streaming 与非 streaming 的首帧结构一致", () => {
    const idle = renderToStaticMarkup(
      <EditInlineDiffContent patch={PATCH} path="demo.ts" />
    );
    const streaming = renderToStaticMarkup(
      <EditInlineDiffContent patch={PATCH} path="demo.ts" streaming />
    );

    expect(streaming).toBe(idle);
  });
});
