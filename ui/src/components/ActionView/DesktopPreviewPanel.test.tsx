import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import DesktopPreviewPanel from "./DesktopPreviewPanel";
import { resolveDesktopPreviewUrl } from "./desktopPreviewUrl";

describe("DesktopPreviewPanel", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("拒绝 javascript: 地址并且不渲染预览", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);

    expect(resolveDesktopPreviewUrl("javascript:alert(1)")).toBeNull();
    expect(warn).toHaveBeenCalled();

    const html = renderToStaticMarkup(
      <DesktopPreviewPanel streamUrl="javascript:alert(1)" onClose={() => undefined} />
    );
    expect(html).toBe("");
  });

  it("允许 https 地址并正常渲染", () => {
    const url = "https://desktop.example.com/session/1";

    expect(resolveDesktopPreviewUrl(url)).toBe(url);

    const html = renderToStaticMarkup(
      <DesktopPreviewPanel streamUrl={url} onClose={() => undefined} />
    );
    expect(html).toContain(`href="${url}"`);
    expect(html).toContain(`src="${url}"`);
  });
});
