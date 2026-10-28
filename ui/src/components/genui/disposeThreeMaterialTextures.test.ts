import { describe, expect, it, vi } from "vitest";
import { disposeMaterialTextures } from "./disposeThreeMaterialTextures";

const texture = () => ({ dispose: vi.fn() });

describe("disposeMaterialTextures", () => {
  it("释放材质上每个纹理槽位各一次", () => {
    const map = texture();
    const normalMap = texture();

    disposeMaterialTextures({
      map,
      normalMap
    });

    expect(map.dispose).toHaveBeenCalledTimes(1);
    expect(normalMap.dispose).toHaveBeenCalledTimes(1);
  });

  it("多个材质共享同一纹理时只释放一次", () => {
    const shared = texture();
    const disposed = new Set<{ dispose?:() => void }>();

    disposeMaterialTextures({ map: shared }, disposed);
    disposeMaterialTextures({
      map: shared,
      emissiveMap: shared
    }, disposed);

    expect(shared.dispose).toHaveBeenCalledTimes(1);
  });

  it("空槽位与 null 材质不抛异常", () => {
    expect(() => disposeMaterialTextures(null)).not.toThrow();
    expect(() => disposeMaterialTextures(undefined)).not.toThrow();
    expect(() => disposeMaterialTextures({})).not.toThrow();
    expect(() => disposeMaterialTextures({ map: null })).not.toThrow();
  });

  it("没有 dispose 方法的对象被跳过而不是崩溃", () => {
    expect(() => disposeMaterialTextures({ map: {} })).not.toThrow();
  });
});
