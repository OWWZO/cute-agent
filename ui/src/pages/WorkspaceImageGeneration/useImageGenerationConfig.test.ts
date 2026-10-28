import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createDefaultConfig, loadStoredConfig } from "./useImageGenerationConfig";
import { IMAGE_GENERATION_STORAGE_KEY } from "./utils";

const getItem = vi.fn();

describe("image generation config", () => {
  beforeEach(() => {
    getItem.mockReset();
    vi.stubGlobal("localStorage", {
      getItem,
      setItem: vi.fn()
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("默认配置不包含 API 凭据字段", () => {
    const config = createDefaultConfig() as unknown as Record<string, unknown>;

    expect(config).not.toHaveProperty("apiKey");
    expect(config).not.toHaveProperty("baseUrl");
  });

  // 回归：旧版本会把 baseUrl / apiKey 写进 localStorage，
  // 读取时必须丢弃，避免把历史密钥再次写回存储。
  it("读取旧版本配置时丢弃 apiKey / baseUrl", () => {
    getItem.mockReturnValue(
      JSON.stringify({
        apiKey: "sk-leaked",
        baseUrl: "https://evil.example",
        model: "gpt-image-2",
        size: "1024x1024",
        n: 2,
        batchMode: false,
      })
    );

    const config = loadStoredConfig() as unknown as Record<string, unknown>;

    expect(getItem).toHaveBeenCalledWith(IMAGE_GENERATION_STORAGE_KEY);
    expect(config).not.toHaveProperty("apiKey");
    expect(config).not.toHaveProperty("baseUrl");
    expect(config.model).toBe("gpt-image-2");
    expect(config.n).toBe(2);
    expect(config.batchMode).toBe(false);
  });
});
