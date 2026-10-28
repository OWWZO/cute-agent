/**
 * 释放材质上挂的纹理。
 *
 * `Material.dispose()` 只释放材质自身，**不会**释放 `map` / `normalMap` 等槽位里的
 * Texture —— GLTF 模型通常带多张贴图，卡片卸载或重跑 effect 后 GPU 纹理仍在占用。
 *
 * 同一张贴图可能被多个材质共享，所以调用方要跨材质复用同一个 `disposed` Set，
 * 避免对同一纹理重复 `dispose()`。
 */
const TEXTURE_SLOTS = [
  "map",
  "normalMap",
  "roughnessMap",
  "metalnessMap",
  "emissiveMap",
  "aoMap",
  "alphaMap",
  "bumpMap",
  "displacementMap",
  "envMap",
  "clearcoatMap",
  "clearcoatNormalMap",
  "clearcoatRoughnessMap",
  "sheenColorMap",
  "sheenRoughnessMap",
  "specularMap",
  "specularColorMap",
  "specularIntensityMap",
  "iridescenceMap",
  "iridescenceThicknessMap",
  "transmissionMap",
  "thicknessMap",
  "anisotropyMap",
] as const;

export function disposeMaterialTextures(
  material: { [key: string]: unknown } | null | undefined,
  disposed: Set<{ dispose?: () => void }> = new Set()
) {
  if (!material) return;
  for (const slot of TEXTURE_SLOTS) {
    const texture = material[slot] as { dispose?: () => void } | null | undefined;
    if (!texture || disposed.has(texture)) continue;
    disposed.add(texture);
    texture.dispose?.();
  }
}
