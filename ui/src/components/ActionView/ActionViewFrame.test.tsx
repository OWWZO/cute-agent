import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import ActionViewFrame from "./ActionViewFrame";

describe("ActionViewFrame", () => {
  it("renders the icon-only back control as a named button", () => {
    const html = renderToStaticMarkup(
      <ActionViewFrame titleNode="Preview" onClickTitle={() => undefined}>
        content
      </ActionViewFrame>
    );

    expect(html).toContain('type="button"');
    expect(html).toContain('aria-label="返回"');
  });
});
