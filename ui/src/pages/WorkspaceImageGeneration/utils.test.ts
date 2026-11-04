import { describe, expect, it, vi } from "vitest";

import { findImageInText, hasCanvasDrawing } from "./utils";

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

describe("findImageInText", () => {
  it("不再把任意长 base64 字母串误判为图片", () => {
    expect(findImageInText("A".repeat(300))).toBeNull();
  });

  it("识别图片 magic prefix 后只截取连续 base64 字符", () => {
    const base64 = `iVBOR${"A".repeat(300)}`;

    expect(findImageInText(`${base64}","summary":"not-image-data`)).toMatchObject({
      dataUrl: `data:image/png;base64,${base64}`,
    });
  });

  it("Data URL 在首个非 base64 字符处停止，不吞掉后续 JSON 字段", () => {
    const base64 = `R0lGOD${"B".repeat(300)}`;
    const result = findImageInText(
      `data:image/gif;base64,${base64}","next":"secret`
    );

    expect(result).toEqual({ dataUrl: `data:image/gif;base64,${base64}` });
  });
});
