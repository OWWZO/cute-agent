import { useEffect, useState } from "react";

import type { GenerationConfig } from "./types";
import { IMAGE_GENERATION_STORAGE_KEY } from "./utils";

export const createDefaultConfig = (): GenerationConfig => ({
  model: "gpt-image-2",
  mode: "images",
  size: "1024x1024",
  n: 1,
  batchMode: true,
});

export const loadStoredConfig = (): GenerationConfig => {
  const defaults = createDefaultConfig();
  try {
    const raw = localStorage.getItem(IMAGE_GENERATION_STORAGE_KEY);
    if (!raw) {
      return defaults;
    }

    const parsed = JSON.parse(raw) as Partial<GenerationConfig>;
    // 只回填已知的生成参数。旧版本可能把 baseUrl / apiKey 写进 localStorage，
    // 这里显式挑字段，避免把这些历史敏感字段再次写回存储。
    return {
      model:
        typeof parsed.model === "string" && parsed.model.trim()
          ? parsed.model
          : defaults.model,
      mode: parsed.mode === "edits" ? "edits" : defaults.mode,
      size:
        typeof parsed.size === "string" && parsed.size.trim()
          ? parsed.size
          : defaults.size,
      n: Math.max(1, Math.min(10, Number(parsed.n) || defaults.n)),
      batchMode:
        typeof parsed.batchMode === "boolean"
          ? parsed.batchMode
          : defaults.batchMode,
    };
  } catch {
    return defaults;
  }
};

/**
 * 本地参数集中在一个 Hook 中，避免页面组件同时维护读取、校验和持久化细节。
 */
export function useImageGenerationConfig() {
  const [config, setConfig] = useState<GenerationConfig>(() => loadStoredConfig());

  useEffect(() => {
    localStorage.setItem(IMAGE_GENERATION_STORAGE_KEY, JSON.stringify(config));
  }, [config]);

  const updateConfig = <K extends keyof GenerationConfig>(
    key: K,
    value: GenerationConfig[K]
  ) => {
    setConfig((previous) => ({
      ...previous,
      [key]: value,
    }));
  };

  return {
    config,
    setConfig,
    updateConfig,
  };
}
