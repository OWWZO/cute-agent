import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const css = readFileSync(
  new URL("./github-markdown.css", import.meta.url),
  "utf8"
);

describe("github markdown theme variables", () => {
  it("defines light variables unconditionally for explicit light themes", () => {
    expect(css).toContain('.markdown-body, [data-theme="light"] {');
    expect(css).toContain("--fgColor-default: #1f2328;");
  });

  it("defines matching dark variables for system and explicit dark themes", () => {
    expect(css).toContain("@media (prefers-color-scheme: dark)");
    expect(css).toContain('.markdown-body:not([data-theme="light"]) {');
    expect(css).toContain('[data-theme="dark"] {');
    expect(css).toContain("--fgColor-default: #f0f6fc;");
    expect(css).toContain("--borderColor-default: #3d444d;");
  });
});
