import { describe, expect, it, vi } from "vitest";

import {
  resolveLoadedEditorImages,
  revokeEditorImageObjectUrls,
} from "./useImageEditor";
import type { EditorImageItem } from "./types";

function buildItem(objectUrl: string): EditorImageItem {
  return {
    id: objectUrl,
    file: new File(["image"], "image.png", { type: "image/png" }),
    objectUrl,
    naturalWidth: 1,
    naturalHeight: 1,
    maskDataUrl: null,
  };
}

describe("useImageEditor helpers", () => {
  it("卸载编辑器时会释放所有 objectUrl", () => {
    const revokeSpy = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => {});

    revokeEditorImageObjectUrls([
      { objectUrl: "blob:img-1" },
      { objectUrl: "blob:img-2" },
    ]);

    expect(revokeSpy).toHaveBeenCalledWith("blob:img-1");
    expect(revokeSpy).toHaveBeenCalledWith("blob:img-2");
  });

  it("组件在图片加载期间卸载时释放新创建的 objectUrl 且不入队", async () => {
    const revoke = vi.fn();
    const result = await resolveLoadedEditorImages(
      [new File(["image"], "image.png", { type: "image/png" })],
      () => false,
      async () => buildItem("blob:pending"),
      revoke
    );

    expect(result).toBeNull();
    expect(revoke).toHaveBeenCalledWith([expect.objectContaining({ objectUrl: "blob:pending" })]);
  });

  it("单张图片创建失败不会产生未处理 rejection，其余图片仍可入队", async () => {
    const warning = vi.spyOn(console, "error").mockImplementation(() => {});
    const files = [
      new File(["one"], "one.png", { type: "image/png" }),
      new File(["two"], "two.png", { type: "image/png" }),
    ];

    const result = await resolveLoadedEditorImages(
      files,
      () => true,
      async (file) => {
        if (file.name === "two.png") {
          throw new Error("createObjectURL failed");
        }
        return buildItem("blob:one");
      },
      vi.fn()
    );

    expect(result).toEqual([expect.objectContaining({ objectUrl: "blob:one" })]);
    expect(warning).toHaveBeenCalledWith(
      "[useImageEditor] 图片加载失败",
      expect.any(Error)
    );
    warning.mockRestore();
  });
});
