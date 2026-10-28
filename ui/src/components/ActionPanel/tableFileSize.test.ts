import { describe, expect, it } from "vitest";
import {
  TABLE_MAX_FILE_SIZE_BYTES,
  estimateUtf8Bytes,
  isTableFileTooLarge,
} from "./tableFileSize";

describe("tableFileSize", () => {
  it("恰好 25MB 通过，超过 1 字节即判超限", () => {
    expect(isTableFileTooLarge(TABLE_MAX_FILE_SIZE_BYTES)).toBe(false);
    expect(isTableFileTooLarge(TABLE_MAX_FILE_SIZE_BYTES + 1)).toBe(true);
  });

  it("空文件不判超限", () => {
    expect(isTableFileTooLarge(0)).toBe(false);
  });

  it("UTF-8 字节估算不低估 ASCII / 中文 / emoji", () => {
    expect(estimateUtf8Bytes("abc")).toBe(3);
    expect(estimateUtf8Bytes("中文")).toBe(6);
    expect(estimateUtf8Bytes("😀")).toBe(4);
    // 混排时等于各段之和
    expect(estimateUtf8Bytes("a中😀")).toBe(1 + 3 + 4);
  });
});
