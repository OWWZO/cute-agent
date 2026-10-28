import { describe, expect, it } from "vitest";
import { findCurrentPageByOffsets } from "./pageOffsets";

const OFFSETS = [0, 0, 800, 1600];

describe("findCurrentPageByOffsets", () => {
  it("目标落在两页之间时返回上一页", () => {
    expect(findCurrentPageByOffsets(OFFSETS, 0)).toBe(1);
    expect(findCurrentPageByOffsets(OFFSETS, 500)).toBe(1);
    expect(findCurrentPageByOffsets(OFFSETS, 799)).toBe(1);
    expect(findCurrentPageByOffsets(OFFSETS, 800)).toBe(2);
    expect(findCurrentPageByOffsets(OFFSETS, 1599)).toBe(2);
    expect(findCurrentPageByOffsets(OFFSETS, 1600)).toBe(3);
  });

  it("超出最后一页时返回最后一页", () => {
    expect(findCurrentPageByOffsets(OFFSETS, 2000)).toBe(3);
  });

  it("目标小于第一页偏移时返回 1", () => {
    expect(findCurrentPageByOffsets([0, 120, 900], -50)).toBe(1);
  });

  it("空数组 / 只有占位元素时返回 1", () => {
    expect(findCurrentPageByOffsets([], 500)).toBe(1);
    expect(findCurrentPageByOffsets([0], 500)).toBe(1);
  });
});
