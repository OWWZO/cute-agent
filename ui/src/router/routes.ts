export const ROUTES = {
  HOME: "/",
  LOGIN: "/login",
  REGISTER: "/register",
  FEATURED_CONVERSATIONS: "/featured-conversations",
  FEATURED_CONVERSATION_DETAIL: "/featured-conversations/:featuredId",
  WORKSPACE: "/workspace",
  WORKSPACE_MRAG: "/workspace/mrag",
  WORKSPACE_IMAGE_GENERATION: "/workspace/image-generation",
  WORKSPACE_SOP: "/workspace/sop",
  WORKSPACE_SUB_AGENTS: "/workspace/sub-agents",
  WORKSPACE_MODELS: "/workspace/models",
  WORKSPACE_CAPABILITIES: "/workspace/capabilities",
  TASK_REVIEW: "/task-review",
  TASK_REVIEW_DETAIL: "/task-review/:jobId",
  OPENUI_DEMO: "/openui-demo",
  NOT_FOUND: "*",
} as const;

const CONTROL_CHAR_RE = /[\u0000-\u001f\u007f]/u;

function decodePathRepeatedly(path: string): string | null {
  let decoded = path;
  for (let index = 0; index < 8; index += 1) {
    try {
      const next = decodeURIComponent(decoded);
      if (next === decoded) {
        return decoded;
      }
      decoded = next;
    } catch {
      return null;
    }
  }
  return null;
}

export function normalizeReturnUrl(value: string | null | undefined) {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.includes("\\")) {
    return ROUTES.HOME;
  }

  const path = value.split(/[?#]/u, 1)[0] ?? "";
  const decodedPath = decodePathRepeatedly(path);
  if (
    decodedPath === null ||
    decodedPath.startsWith("//") ||
    decodedPath.includes("\\") ||
    CONTROL_CHAR_RE.test(decodedPath)
  ) {
    return ROUTES.HOME;
  }

  try {
    const currentOrigin =
      typeof window === "undefined" ? "http://localhost" : window.location.origin;
    const resolved = new URL(value, currentOrigin);
    if (resolved.origin !== new URL(currentOrigin).origin) {
      return ROUTES.HOME;
    }
  } catch {
    return ROUTES.HOME;
  }

  return value;
}

export function buildLoginPath(returnUrl: string) {
  const params = new URLSearchParams({ returnUrl: normalizeReturnUrl(returnUrl) });
  return `${ROUTES.LOGIN}?${params.toString()}`;
}

export function buildFeaturedConversationDetailPath(featuredId: string) {
  return ROUTES.FEATURED_CONVERSATION_DETAIL.replace(
    ":featuredId",
    encodeURIComponent(featuredId)
  );
}
