import api from "./index";
import type {
  ConversationHistoryDetail,
  ConversationHistoryPage,
  ConversationRunReplay,
} from "./agentConversation";

export interface FeaturedConversationCard {
  featuredId: string;
  sessionId: string;
  title: string;
  summary: string;
  coverUrl?: string;
  tags: string[];
  publishedAt?: string;
  contentLastActiveAt?: string;
}

export interface FeaturedConversationDetail extends FeaturedConversationCard {
  status?: string;
  contentAvailable: boolean;
  contentUnavailableReason?: string;
  historyDetail: ConversationHistoryPage | ConversationHistoryDetail | null;
}

export interface FeaturedConversationPage {
  total: number;
  list: FeaturedConversationCard[];
}

const FEATURED_CONVERSATION_BASE = "/api/agent/featured-conversations";

export const featuredConversationApi = {
  listHome: (limit = 6) =>
    api.get<FeaturedConversationCard[]>(`${FEATURED_CONVERSATION_BASE}/home`, {limit,}) as unknown as Promise<FeaturedConversationCard[]>,
  list: (params: { pageNo: number; pageSize: number }) =>
    api.get<FeaturedConversationPage>(FEATURED_CONVERSATION_BASE, params) as unknown as Promise<FeaturedConversationPage>,
  detail: (featuredId: string) =>
    api.get<FeaturedConversationDetail>(`${FEATURED_CONVERSATION_BASE}/${featuredId}`) as unknown as Promise<FeaturedConversationDetail>,
  getFeaturedRunReplay: (featuredId: string, requestId: string) =>
    api.get<ConversationRunReplay>(
      `${FEATURED_CONVERSATION_BASE}/${encodeURIComponent(featuredId)}/runs/${encodeURIComponent(requestId)}/replay`
    ) as unknown as Promise<ConversationRunReplay>,
};
