import { afterEach, describe, expect, it, vi } from "vitest";

import {
  isSafeBrowserUrl,
  normalizeFileUrlForBrowser,
  toFilePreviewUrl,
} from "./fileUrl";

describe("normalizeFileUrlForBrowser", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should rewrite loopback tool url to current origin tool path", () => {
    vi.stubGlobal("window", {
      location: {
        host: "localhost:3000",
        hostname: "localhost",
        protocol: "http:",
      },
    });

    expect(
      normalizeFileUrlForBrowser("http://127.0.0.1:1601/v1/file_tool/preview/req/demo.html")
    ).toBe("http://localhost:3000/tool/v1/file_tool/preview/req/demo.html");
  });

  it("opens the preview endpoint when only a download url is available", () => {
    vi.stubGlobal("window", {
      location: {
        host: "localhost:3000",
        hostname: "localhost",
        protocol: "http:",
      },
    });

    expect(
      toFilePreviewUrl("http://127.0.0.1:1601/v1/file_tool/download/req/demo.pdf")
    ).toBe("http://localhost:3000/tool/v1/file_tool/preview/req/demo.pdf");
    expect(toFilePreviewUrl("https://example.com/article")).toBe(
      "https://example.com/article"
    );
  });
});

describe("isSafeBrowserUrl", () => {
  it("allows web, relative and explicitly allowed protocols", () => {
    expect(isSafeBrowserUrl("https://example.com/file.pdf")).toBe(true);
    expect(isSafeBrowserUrl("/tool/v1/file_tool/preview/req/a.md")).toBe(true);
    expect(isSafeBrowserUrl("blob:https://example.com/id", ["blob:"])).toBe(true);
    expect(isSafeBrowserUrl("mailto:hello@example.com", ["mailto:"])).toBe(true);
  });

  it.each([
    "javascript:alert(1)",
    "java\nscript:alert(1)",
    "\u0000javascript:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "file:///C:/Windows/win.ini",
    "vbscript:msgbox(1)",
  ])("rejects unsafe schemes after URL parsing: %s", (url) => {
    expect(isSafeBrowserUrl(url, ["http:", "https:", "blob:"])).toBe(false);
  });
});
