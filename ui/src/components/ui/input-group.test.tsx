import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { InputGroup, InputGroupButton, InputGroupInput } from "./input-group";

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

  it("只跟随输入控件进入禁用态，不因内部按钮禁用而整体变淡", () => {
    const html = renderToStaticMarkup(
      <InputGroup>
        <InputGroupInput />
        <InputGroupButton disabled>发送</InputGroupButton>
      </InputGroup>
    );

    expect(html).toContain(
      "has-[[data-slot=input-group-control]:disabled]:opacity-50"
    );
    expect(html).not.toContain("has-disabled:opacity-50");
  });
});
