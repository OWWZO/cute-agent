import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { InputGroup } from "./input-group";

describe("InputGroup", () => {
  it("uses tailwind-merge for conflicting caller classes", () => {
    const html = renderToStaticMarkup(
      <InputGroup className="h-10">
        <input />
      </InputGroup>
    );

    expect(html).toContain("h-10");
    expect(html).not.toMatch(/class="[^"]*\bh-8\b[^"]*"/);
  });
});
