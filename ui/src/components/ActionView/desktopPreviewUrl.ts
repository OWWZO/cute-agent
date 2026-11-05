import { isSafeBrowserUrl } from "@/utils/fileUrl";

const DESKTOP_PREVIEW_PROTOCOLS = ["http:", "https:", "blob:"] as const;

/** 只允许浏览器可安全加载的协议，拒绝 javascript:/data:/file: 等地址。 */
export function resolveDesktopPreviewUrl(
  raw: string | null | undefined
): string | null {
  const value = (raw || "").trim();
  if (!value) {
    return null;
  }
  if (!isSafeBrowserUrl(value, DESKTOP_PREVIEW_PROTOCOLS)) {
    console.warn("[DesktopPreviewPanel] 已拒绝非 http(s)/blob 的桌面地址");
    return null;
  }
  return value;
}
