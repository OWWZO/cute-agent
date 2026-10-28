import { describe, expect, it } from "vitest";
import { estimateSourceBytes, truncateSourceToBytes } from "./sourceCodeBytes";

describe("sourceCodeBytes", () => {
  it("UTF-8 字节估算覆盖 ASCII / 中文 / emoji", () => {
    expect(estimateSourceBytes("abc")).toBe(3);
    expect(estimateSourceBytes("中文")).toBe(6);
    expect(estimateSourceBytes("😀")).toBe(4);
  });

  it("未超限时原样返回", () => {
    expect(truncateSourceToBytes("abc", 10)).toBe("abc");
    expect(truncateSourceToBytes("中文", 6)).toBe("中文");
  });

  it("按字节截断 ASCII 不会多截", () => {
    expect(truncateSourceToBytes("abcdef", 3)).toBe("abc");
  });

  // 按字符 slice(0, 3) 会切出半个汉字；按字节截断只允许边界字符被替换。
  it("按字节截断中文，不产生半个原始字符", () => {
    const truncated = truncateSourceToBytes("中文", 4);
    expect(truncated.startsWith("中")).toBe(true);
    expect(truncated).not.toBe("中文");
  });

  it("按字节截断 emoji 不抛异常", () => {
    expect(() => truncateSourceToBytes("😀😀", 2)).not.toThrow();
    expect(truncateSourceToBytes("😀😀", 2)).not.toBe("😀😀");
  });
});
