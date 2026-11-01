import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import PatchViewer from "./PatchViewer";

describe("PatchViewer", () => {
  it("超大 patch 的降级视图把截断标记渲染为已省略提示", () => {
    const body = Array.from({ length: 1200 }, (_, index) => `+line ${index}`);
    const patch = ["--- a/x", "+++ b/x", "@@", ...body].join("\n");

    const html = renderToStaticMarkup(<PatchViewer patch={patch} />);

    expect(html).toContain("已省略");
    expect(html).not.toContain("__CUTE_DIFF_TRUNCATED__");
  });
});
