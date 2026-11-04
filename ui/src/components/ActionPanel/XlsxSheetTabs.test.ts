import { describe, expect, it, vi } from "vitest";

import { focusSheetTab } from "./XlsxSheetTabs";

describe("focusSheetTab", () => {
  it("does not throw when the requested tab is missing", () => {
    const item = vi.fn(() => null);

    expect(
      focusSheetTab(
        { item } as unknown as Pick<NodeListOf<HTMLElement>, "item">,
        3
      )
    ).toBe(false);
  });

  it("focuses a tab when the DOM node exists", () => {
    const focus = vi.fn();
    const item = vi.fn(() => ({ focus }) as unknown as HTMLElement);

    expect(
      focusSheetTab(
        { item } as unknown as Pick<NodeListOf<HTMLElement>, "item">,
        1
      )
    ).toBe(true);
    expect(focus).toHaveBeenCalledTimes(1);
  });
});
