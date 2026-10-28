import { describe, expect, it } from "vitest";
import { safeStringify } from "./safeStringify";

describe("safeStringify", () => {
  it("正常值按 JSON 输出", () => {
    expect(safeStringify({ a: 1 })).toBe(JSON.stringify({ a: 1 }, null, 2));
  });

  it("循环引用不抛异常，返回占位文本", () => {
    const value: Record<string, unknown> = {};
    value.self = value;

    expect(() => safeStringify(value)).not.toThrow();
    expect(safeStringify(value)).toBe("[无法序列化的结果]");
  });

  it("BigInt 不抛异常，返回占位文本", () => {
    expect(safeStringify({ n: BigInt(1) })).toBe("[无法序列化的结果]");
  });

  it("undefined 等不可序列化值返回空串", () => {
    expect(safeStringify(undefined)).toBe("");
  });
});
