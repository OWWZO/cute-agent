import api from "./index";

// 精选会话管理 API，online/offline 只改变发布状态，queryList 负责后台分页查询。
export interface FeaturedConversationAdminQuery {
  status?: string;
  sessionId?: string;
  title?: string;
  pageNo?: number;
  pageSize?: number;
}

export interface FeaturedConversationAdminUpsertPayload {
  featuredId?: string;
  sessionId: string;
  title: string;
  summary: string;
  coverResourceKey?: string;
  coverUrl?: string;
  tags: string[];
  sortOrder: number;
  operator: string;
}

export interface FeaturedConversationAdminRecord {
  featuredId: string;
  sessionId: string;
  title: string;
  summary: string;
  tags: string[];
  coverUrl?: string;
  sortOrder?: number;
  status?: string;
  publishedAt?: string;
  updatedAt?: string;
}

export interface FeaturedConversationAdminPage {
  total: number;
  list: FeaturedConversationAdminRecord[];
}

const FEATURED_CONVERSATION_ADMIN_BASE = "/api/v1/admin/featured-conversations";

export const featuredConversationAdminApi = {
  queryList: (payload: FeaturedConversationAdminQuery) =>
    api.post<FeaturedConversationAdminPage>(
      `${FEATURED_CONVERSATION_ADMIN_BASE}/query-list`,
      payload
    ) as unknown as Promise<FeaturedConversationAdminPage>,
  create: (payload: FeaturedConversationAdminUpsertPayload) =>
    api.post<boolean>(`${FEATURED_CONVERSATION_ADMIN_BASE}/create`, payload) as unknown as Promise<boolean>,
  update: (payload: FeaturedConversationAdminUpsertPayload) =>
    api.put<boolean>(`${FEATURED_CONVERSATION_ADMIN_BASE}/update`, payload) as unknown as Promise<boolean>,
  online: (featuredId: string, operator: string) =>
    api.post<boolean>(
      `${FEATURED_CONVERSATION_ADMIN_BASE}/online/${featuredId}`,
      undefined,
      { params: { operator } }
    ) as unknown as Promise<boolean>,
  offline: (featuredId: string, operator: string) =>
    api.post<boolean>(
      `${FEATURED_CONVERSATION_ADMIN_BASE}/offline/${featuredId}`,
      undefined,
      { params: { operator } }
    ) as unknown as Promise<boolean>,
};
