import { ROUTES } from "@/router/routes";

/**
 * 侧栏视图的唯一定义处。
 *
 * 工作台模块（mrag / sop / …）只有一套导航：Home 页内切换。
 * `/workspace/*` 旧路由保留为重定向入口，落到 `/?view=<key>`，
 * 因此 view 与 URL 必须由这里统一换算，避免两套模型各写一份枚举。
 */
export const SIDEBAR_VIEWS = [
  "chat",
  "mrag",
  "image-generation",
  "sop",
  "sub-agents",
  "models",
  "capabilities",
  "tools",
  "featured",
  "channels",
] as const;

export type SidebarView = (typeof SIDEBAR_VIEWS)[number];

const VIEW_QUERY_KEY = "view";
const DEFAULT_SIDEBAR_VIEW: SidebarView = "chat";

export const isSidebarView = (value: string | null): value is SidebarView =>
  value !== null && (SIDEBAR_VIEWS as readonly string[]).includes(value);

/** 从 query 还原视图；非法或缺省都回落到对话页。 */
export const readViewFromSearch = (searchParams: URLSearchParams): SidebarView => {
  const view = searchParams.get(VIEW_QUERY_KEY);
  return isSidebarView(view) ? view : DEFAULT_SIDEBAR_VIEW;
};

/** 对话页不带 query，其余视图统一挂在 `?view=` 上。 */
export const buildHomeViewPath = (view: SidebarView): string => {
  if (view === DEFAULT_SIDEBAR_VIEW) {
    return ROUTES.HOME;
  }
  const searchParams = new URLSearchParams({ [VIEW_QUERY_KEY]: view });
  return `${ROUTES.HOME}?${searchParams.toString()}`;
};
