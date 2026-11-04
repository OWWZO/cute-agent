import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import SearchListRenderer from "./SearchListRenderer";

describe("SearchListRenderer URL safety", () => {
  it.each([
    "javascript:alert(1)",
    "java\nscript:alert(1)",
    "data:text/html,<script>alert(1)</script>",
  ])("does not expose an unsafe result as a link: %s", (url) => {
    const html = renderToStaticMarkup(
      createElement(SearchListRenderer, {
        list: [{ name: "unsafe", pageContent: "content", url }],
      })
    );

    expect(html).not.toContain('role="link"');
    expect(html).not.toContain('tabindex="0"');
  });
});
