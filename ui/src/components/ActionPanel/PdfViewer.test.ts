import { describe, expect, it, vi } from "vitest";

vi.mock("react-pdf", () => ({
  Document: () => null,
  Page: () => null,
  pdfjs: { GlobalWorkerOptions: {} },
}));

import { createInitialPdfViewerState } from "./PdfViewer";

describe("PdfViewer source reset", () => {
  it("resets scale, page sizes and pagination when a source changes", () => {
    const initial = createInitialPdfViewerState();

    expect(initial).toMatchObject({
      numPages: null,
      currentPage: 1,
      pageInput: "1",
      pageSizes: {},
    });
    expect(initial.scale).toBe(initial.committedScale);
  });
});
