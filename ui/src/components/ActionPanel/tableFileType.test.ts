import { describe, expect, it } from "vitest";
import { resolveTableExtension } from "./tableFileType";

describe("resolveTableExtension", () => {
  it("忽略 query / hash 后识别 xlsx", () => {
    expect(resolveTableExtension(undefined, "https://files.test/report.xlsx?token=1")).toBe(
      "xlsx"
    );
  });

  it("有 fileName 时优先使用文件名", () => {
    expect(
      resolveTableExtension("report.xlsx", "https://files.test/api/download/123")
    ).toBe("xlsx");
  });

  it("无扩展名返回空串", () => {
    expect(resolveTableExtension(undefined, "https://files.test/report")).toBe("");
  });

  it("文件名没有扩展名时回退到 URL", () => {
    expect(
      resolveTableExtension("report", "https://files.test/report.xlsx?token=1")
    ).toBe("xlsx");
  });
});
