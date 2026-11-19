import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const globalCss = readFileSync(
  resolve(import.meta.dirname, "../../global.css"),
  "utf8"
);

describe("collapsed workspace layout", () => {
  it("hides the retained workspace panel and gives chat the full row", () => {
    expect(globalCss).toContain(
      '.cute-chat-workspace-layout[data-workspace-open="false"] > #chat'
    );
    expect(globalCss).toContain(
      '.cute-chat-workspace-layout[data-workspace-open="false"] > #workspace'
    );
  });
});
