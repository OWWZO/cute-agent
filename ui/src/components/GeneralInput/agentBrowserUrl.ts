import { isSafeBrowserUrl } from "@/utils/fileUrl";

const AGENT_BROWSER_URL_PROTOCOLS = ["http:", "https:"] as const;

/** ensure 接口返回的地址会直接用于弹窗导航，非 http(s) 一律拒绝。 */
export function resolveAgentBrowserLiveViewUrl(
  raw?: string | null
): string | null {
  const value = (raw || "").trim();
  if (!value) {
    return null;
  }
  return isSafeBrowserUrl(value, AGENT_BROWSER_URL_PROTOCOLS) ? value : null;
}
