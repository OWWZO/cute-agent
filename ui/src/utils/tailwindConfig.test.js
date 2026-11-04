import { describe, expect, it, vi } from "vitest";

import config, { scrollbarUtilities } from "../../tailwind.config.js";

describe("tailwind scrollbar utilities", () => {
  it("通过真实 plugin 注册 scrollbar-hidden", () => {
    const addUtilities = vi.fn();
    config.plugins[0].handler({ addUtilities });

    expect(addUtilities).toHaveBeenCalledWith(scrollbarUtilities);
    expect(addUtilities.mock.calls[0][0][".scrollbar-hidden"]["scrollbar-width"]).toBe("none");
  });
});
