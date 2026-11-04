/** 模型接入页展示元数据 */

export type ProviderId = "openai" | "deepseek" | "moonshot" | "custom";

export type ProviderPreset = {
  id: ProviderId;
  label: string;
  baseUrl: string;
  completionsPath: string;
  keyHint: string;
};

/** 常用厂商预设：选中后自动填 baseUrl / path，key 仍手填 */
const DEFAULT_COMPLETIONS_PATH = "/chat/completions";

export const PROVIDER_PRESETS: ProviderPreset[] = [
  {
    id: "openai",
    label: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    completionsPath: DEFAULT_COMPLETIONS_PATH,
    keyHint: "platform.openai.com → API keys",
  },
  {
    id: "deepseek",
    label: "DeepSeek",
    baseUrl: "https://api.deepseek.com/v1",
    completionsPath: DEFAULT_COMPLETIONS_PATH,
    keyHint: "platform.deepseek.com",
  },
  {
    id: "moonshot",
    label: "Kimi / Moonshot",
    baseUrl: "https://api.moonshot.cn/v1",
    completionsPath: DEFAULT_COMPLETIONS_PATH,
    keyHint: "platform.moonshot.cn",
  },
  {
    id: "custom",
    label: "自定义",
    baseUrl: "",
    completionsPath: DEFAULT_COMPLETIONS_PATH,
    keyHint: "手填 Base URL 与 Key",
  },
];

export function matchProviderId(baseUrl?: string): ProviderId {
  const u = (baseUrl || "").trim().toLowerCase();
  if (!u) return "custom";
  const hit = PROVIDER_PRESETS.find(
    (p) => p.baseUrl && u.startsWith(p.baseUrl.toLowerCase())
  );
  return hit?.id ?? "custom";
}
