import { describe, expect, it, vi } from "vitest";

import { invokeCodeBlockClick } from "./code-block";

describe("CodeBlockCopyButton click composition", () => {
  it("keeps the copy action when callers provide their own click handler", () => {
    const event = {} as React.MouseEvent<HTMLButtonElement>;
    const handleCopy = vi.fn();
    const onClick = vi.fn();

    invokeCodeBlockClick(event, handleCopy, onClick);

    expect(handleCopy).toHaveBeenCalledTimes(1);
    expect(onClick).toHaveBeenCalledWith(event);
  });
});
