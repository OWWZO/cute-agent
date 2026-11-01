import { describe, expect, it, vi } from "vitest";

import { hasCanvasDrawing } from "./utils";

describe("hasCanvasDrawing", () => {
  it("零尺寸画布返回 false，不读取像素", () => {
    const getContext = vi.fn();
    const canvas = {
      width: 0,
      height: 10,
      getContext,
    } as unknown as HTMLCanvasElement;

    expect(hasCanvasDrawing(canvas)).toBe(false);
    expect(getContext).not.toHaveBeenCalled();
  });

  it("普通画布扫描 alpha 并识别绘制内容", () => {
    const data = new Uint8ClampedArray(3 * 2 * 4);
    data[3] = 255;
    const getImageData = vi.fn(() => ({ data }));
    const canvas = {
      width: 3,
      height: 2,
      getContext: () => ({ getImageData }),
    } as unknown as HTMLCanvasElement;

    expect(hasCanvasDrawing(canvas)).toBe(true);
    expect(getImageData).toHaveBeenCalledWith(0, 0, 3, 2);
  });

  it("大画布按行和列步长采样", () => {
    const getImageData = vi.fn((_x: number, y: number, width: number) => {
      const data = new Uint8ClampedArray(width * 4);
      if (y === 4) data[4 * 4 + 3] = 255;
      return { data };
    });
    const canvas = {
      width: 1001,
      height: 1000,
      getContext: () => ({ getImageData }),
    } as unknown as HTMLCanvasElement;

    expect(hasCanvasDrawing(canvas)).toBe(true);
    expect(getImageData).toHaveBeenCalledTimes(2);
    expect(getImageData).toHaveBeenNthCalledWith(2, 0, 4, 1001, 1);
  });

  it("tainted canvas 读取失败时安全返回 false", () => {
    const getImageData = vi.fn(() => {
      throw new DOMException("tainted", "SecurityError");
    });
    const canvas = {
      width: 2,
      height: 2,
      getContext: () => ({ getImageData }),
    } as unknown as HTMLCanvasElement;
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});

    expect(hasCanvasDrawing(canvas)).toBe(false);
    expect(warning).toHaveBeenCalledOnce();
    warning.mockRestore();
  });
});
