import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { MessageToolbar } from "./MessageToolbar";

describe("MessageToolbar task review", () => {
  it("在终答工具栏显示复盘入口", () => {
    const html = renderToStaticMarkup(
      <MessageToolbar response="已完成" onReview={() => undefined} />
    );
    expect(html).toContain("复盘这次任务");
  });

  it("没有终答文本时也能显示复盘入口", () => {
    const html = renderToStaticMarkup(
      <MessageToolbar onReview={() => undefined} />
    );
    expect(html).toContain("复盘这次任务");
  });
});
