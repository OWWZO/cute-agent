const TOOL_PATH_PREFIX = "/tool/";
const FILE_TOOL_PATH_PREFIX = "/v1/file_tool";
const LEGACY_TOOL_PORT = "1601";
const LEGACY_TOOL_HOSTNAMES = new Set([
  "owwzo.top",
  "www.owwzo.top",
  "owwzo.cloud",
  "www.owwzo.cloud",
]);

export function trimTrailingSlash(url: string): string {
  return (url || "").trim().replace(/\/+$/, "");
}

// 浏览器端只使用当前页面的 origin，避免服务端返回的旧工具地址把资源请求带回历史端口。
function buildCurrentToolOrigin(): string {
  if (typeof window === "undefined") {
    return "";
  }
  return `${window.location.protocol}//${window.location.host}`;
}

function buildCurrentToolBaseUrl(): string {
  const origin = buildCurrentToolOrigin();
  if (!origin) {
    return "";
  }
  return `${origin}/tool`;
}

export function buildFilePreviewUrlForBrowser(
  requestId?: string | null,
  fileName?: string | null
): string {
  const origin = buildCurrentToolOrigin();
  const normalizedRequestId = (requestId || "").trim();
  const normalizedFileName = (fileName || "").trim();
  if (!origin || !normalizedRequestId || !normalizedFileName) {
    return "";
  }
  const encodedPath = normalizedFileName
    .replace(/\\/g, "/")
    .split("/")
    .filter(Boolean)
    .map(encodeURIComponent)
    .join("/");
  return `${origin}/tool/v1/file_tool/preview/${encodeURIComponent(normalizedRequestId)}/${encodedPath}`;
}

// 本地开发端口、旧域名和当前工具路径都可能出现在历史产物中，需要统一迁移到当前页面。
function shouldRewriteToCurrentTool(url: URL): boolean {
  if (typeof window === "undefined") {
    return false;
  }

  const currentHost = window.location.host;
  const currentHostname = window.location.hostname;
  const isLoopback =
    url.hostname === "127.0.0.1" ||
    url.hostname === "localhost";
  const isLegacyTopDomain = LEGACY_TOOL_HOSTNAMES.has(url.hostname);
  const isToolPort = url.port === LEGACY_TOOL_PORT;

  if (isToolPort || (isLoopback && !url.port)) {
    return true;
  }

  if (isLegacyTopDomain && currentHostname !== url.hostname) {
    return true;
  }

  return url.host === currentHost && url.pathname.startsWith(TOOL_PATH_PREFIX);
}

export function normalizeToolBaseUrlForBrowser(rawUrl?: string | null): string {
  const normalized = trimTrailingSlash(rawUrl || "");
  const currentToolBaseUrl = buildCurrentToolBaseUrl();
  const currentOrigin = buildCurrentToolOrigin();

  if (!normalized) {
    return currentToolBaseUrl;
  }

  try {
    // 正常分支保留查询参数和 hash；只有命中旧地址规则时才替换 origin/path。
    const parsed = new URL(normalized, currentOrigin || "https://workspace.local");
    if (!shouldRewriteToCurrentTool(parsed)) {
      return parsed.toString().replace(/\/$/, "");
    }

    if (!currentToolBaseUrl) {
      return parsed.toString().replace(/\/$/, "");
    }

    const currentToolUrl = new URL(currentToolBaseUrl);
    currentToolUrl.pathname = parsed.pathname.startsWith("/tool")
      ? parsed.pathname
      : `/tool${parsed.pathname}`;
    currentToolUrl.search = parsed.search;
    currentToolUrl.hash = parsed.hash;
    return currentToolUrl.toString().replace(/\/$/, "");
  } catch {
    // 部分历史值不是完整 URL，按路径前缀兜底，保证坏数据不会阻断页面渲染。
    if (!currentToolBaseUrl) {
      return normalized;
    }
    if (normalized === "/tool") {
      return currentToolBaseUrl;
    }
    if (normalized.startsWith(TOOL_PATH_PREFIX)) {
      return `${currentOrigin}${normalized}`;
    }
    if (normalized.startsWith("/")) {
      return `${currentOrigin}${normalized}`;
    }
    return normalized;
  }
}

/**
 * 工具代理路径前缀的唯一清单：新增工具路由时只改这里，
 * GenUI 资源解析和下面的相对路径兜底才不会出现一套认识、一套不认识。
 */
export function isToolFilePath(value: string): boolean {
  return (
    value.startsWith(TOOL_PATH_PREFIX) ||
    value.startsWith("preview/") ||
    value.startsWith("/preview/") ||
    value.startsWith("download/") ||
    value.startsWith("/download/")
  );
}

/**
 * 把下载地址收敛到同源预览地址。
 *
 * 部分任务产物只回传 downloadUrl，上游兜底会把它塞进 previewUrl；
 * 「在新窗口打开」必须打开预览端点，而不是让浏览器直接下载文件。
 */
export function toFilePreviewUrl(rawUrl?: string | null): string {
  const normalized = normalizeFileUrlForBrowser(rawUrl);
  if (!normalized) {
    return "";
  }

  try {
    const parsed = new URL(normalized);
    if (!parsed.pathname.includes("/download/")) {
      return normalized;
    }
    parsed.pathname = parsed.pathname.replace("/download/", "/preview/");
    return parsed.toString();
  } catch {
    return normalized;
  }
}

export function normalizeFileUrlForBrowser(rawUrl?: string | null): string {
  const normalized = (rawUrl || "").trim();
  if (!normalized) {
    return "";
  }

  try {
    // 文件资源沿用同一套旧地址迁移规则，但保留文件自身的查询参数和 hash。
    const parsed = new URL(normalized);
    if (!shouldRewriteToCurrentTool(parsed)) {
      return parsed.toString();
    }

    const currentToolBaseUrl = buildCurrentToolBaseUrl();
    if (!currentToolBaseUrl) {
      return parsed.toString();
    }

    const currentToolUrl = new URL(currentToolBaseUrl);
    currentToolUrl.pathname = parsed.pathname.startsWith("/tool/")
      ? parsed.pathname
      : `/tool${parsed.pathname}`;
    currentToolUrl.search = parsed.search;
    currentToolUrl.hash = parsed.hash;
    return currentToolUrl.toString();
  } catch {
    // 相对路径无法被 URL 解析时，仅补当前 origin，不猜测其它外部主机。
    if (normalized.startsWith(TOOL_PATH_PREFIX)) {
      const currentOrigin = buildCurrentToolOrigin();
      return currentOrigin ? `${currentOrigin}${normalized}` : normalized;
    }
    const currentToolBaseUrl = buildCurrentToolBaseUrl();
    if (currentToolBaseUrl && isToolFilePath(normalized)) {
      return normalized.startsWith("/")
        ? `${currentToolBaseUrl}${FILE_TOOL_PATH_PREFIX}${normalized}`
        : `${currentToolBaseUrl}${FILE_TOOL_PATH_PREFIX}/${normalized}`;
    }
    return normalized;
  }
}
