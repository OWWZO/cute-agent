import { describe, expect, it, vi } from "vitest";

import {
  revokePromptInputAttachmentUrls,
  validatePromptInputFiles,
} from "./attachments";

describe("prompt-input attachment helpers", () => {
  it("不符合 accept 的文件应触发 accept 错误", () => {
    const files = [
      new File(["abc"], "demo.exe", { type: "application/x-msdownload" }),
    ];

    const result = validatePromptInputFiles(files, {
      accept: "image/*,.pdf",
      maxFiles: 3,
    });

    expect(result.accepted).toEqual([]);
    expect(result.error?.code).toBe("accept");
  });

  it("扩展名 accept 支持 pptx/json/py/html/glb", () => {
    const files = [
      new File(["{}"], "a.json", { type: "application/json" }),
      new File(["print(1)"], "b.py", { type: "text/x-python" }),
      new File(["<html/>"], "c.html", { type: "text/html" }),
      new File(["deck"], "d.pptx", {
        type: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      }),
      new File(["glTF"], "bear 3d model.glb", { type: "" }),
    ];

    const result = validatePromptInputFiles(files, {
      accept:
        "image/*,application/pdf,.txt,.md,.csv,.xlsx,.docx,.pptx,.json,.py,.html,.glb,.gltf,model/gltf-binary,model/gltf+json",
    });

    expect(result.accepted).toHaveLength(5);
    expect(result.error).toBeUndefined();
  });

  it("空 accept 允许旧版 Office/音视频/压缩包/代码/其它文档", () => {
    const files = [
      new File(["doc"], "a.doc", { type: "application/msword" }),
      new File(["xls"], "b.xls", { type: "application/vnd.ms-excel" }),
      new File(["ppt"], "c.ppt", { type: "application/vnd.ms-powerpoint" }),
      new File(["mp4"], "d.mp4", { type: "video/mp4" }),
      new File(["wav"], "e.wav", { type: "audio/wav" }),
      new File(["zip"], "f.zip", { type: "application/zip" }),
      new File(["js"], "g.js", { type: "text/javascript" }),
      new File(["java"], "h.java", { type: "text/x-java-source" }),
      new File(["rtf"], "i.rtf", { type: "application/rtf" }),
      new File(["epub"], "j.epub", { type: "application/epub+zip" }),
    ];

    const result = validatePromptInputFiles(files, { accept: "" });

    expect(result.accepted).toHaveLength(10);
    expect(result.error).toBeUndefined();
  });

  it("超过 maxFiles 时会截断并返回 max_files 错误", () => {
    const files = [
      new File(["1"], "a.png", { type: "image/png" }),
      new File(["2"], "b.png", { type: "image/png" }),
      new File(["3"], "c.png", { type: "image/png" }),
    ];

    const result = validatePromptInputFiles(files, {
      accept: "image/*",
      maxFiles: 2,
    });

    expect(result.accepted).toHaveLength(2);
    expect(result.error?.code).toBe("max_files");
  });

  it("全部超出 maxFileSize 时返回 max_file_size 错误", () => {
    const files = [
      new File(["12345"], "big.png", { type: "image/png" }),
    ];

    const result = validatePromptInputFiles(files, {
      accept: "image/*",
      maxFileSize: 2,
    });

    expect(result.accepted).toEqual([]);
    expect(result.error?.code).toBe("max_file_size");
  });

  it("部分文件类型不匹配时保留匹配项并返回 partial 错误", () => {
    const files = [
      new File(["1"], "a.png", { type: "image/png" }),
      new File(["2"], "b.exe", { type: "application/x-msdownload" }),
    ];

    const result = validatePromptInputFiles(files, { accept: "image/*" });

    expect(result.accepted.map((file) => file.name)).toEqual(["a.png"]);
    expect(result.error?.code).toBe("partial");
  });

  it("部分文件超过大小上限时保留其余文件并返回 partial 错误", () => {
    const files = [
      new File(["1"], "small.png", { type: "image/png" }),
      new File(["12345"], "big.png", { type: "image/png" }),
    ];

    const result = validatePromptInputFiles(files, {
      accept: "image/*",
      maxFileSize: 2,
    });

    expect(result.accepted.map((file) => file.name)).toEqual(["small.png"]);
    expect(result.error?.code).toBe("partial");
  });

  it("清理附件时会回收 object url", () => {
    const revokeSpy = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => {});

    revokePromptInputAttachmentUrls([
      { url: "blob:file-1" },
      { url: "blob:file-2" },
    ]);

    expect(revokeSpy).toHaveBeenCalledWith("blob:file-1");
    expect(revokeSpy).toHaveBeenCalledWith("blob:file-2");
  });
});
