import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";
import { MessageAttachment } from "./message";

describe("MessageAttachment", () => {
  it("positions the non-image remove control inside the fixed preview box", () => {
    const html = renderToStaticMarkup(
      <TooltipProvider>
        <MessageAttachment
          data={{
            type: "file",
            filename: "notes.txt",
            mediaType: "text/plain",
            url: "https://example.com/notes.txt",
          }}
          onRemove={() => undefined}
        />
      </TooltipProvider>
    );

    expect(html).toContain("absolute top-2 right-2");
  });
});
