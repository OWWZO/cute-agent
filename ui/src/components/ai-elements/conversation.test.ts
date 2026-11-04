import { describe, expect, it, vi } from "vitest";

import { invokeConversationScrollClick } from "./conversation";

describe("ConversationScrollButton click composition", () => {
  it("runs the caller handler and still scrolls to the bottom", () => {
    const event = {} as React.MouseEvent<HTMLButtonElement>;
    const onClick = vi.fn();
    const scrollToBottom = vi.fn();

    invokeConversationScrollClick(event, onClick, scrollToBottom);

    expect(onClick).toHaveBeenCalledWith(event);
    expect(scrollToBottom).toHaveBeenCalledTimes(1);
  });
});
