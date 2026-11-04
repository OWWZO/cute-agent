import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ZoomableImage } from "./zoomable-image";

describe("ZoomableImage", () => {
  it("遮罩文案为空时使用图片 alt 作为按钮可访问名称", () => {
    const html = renderToStaticMarkup(
      <ZoomableImage src="/demo.png" alt="产品架构图" maskText="" />
    );

    expect(html).toContain('aria-label="产品架构图"');
    expect(html).not.toContain('aria-label=""');
  });
});
