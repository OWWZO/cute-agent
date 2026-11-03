import { useCallback, useRef, useState } from "react";

import {
  conversationHistoryApi,
  type ConversationSessionItem,
} from "@/services/agentConversation";

const RECENT_SESSION_PAGE_SIZE = 10;

export function useRecentSessions() {
  const [recentSessions, setRecentSessions] = useState<ConversationSessionItem[]>(
    []
  );
  const [recentSessionsLoading, setRecentSessionsLoading] = useState(false);
  const [recentSessionsLoadingMore, setRecentSessionsLoadingMore] =
    useState(false);
  const [recentSessionsHasMore, setRecentSessionsHasMore] = useState(false);
  const sessionsRef = useRef<ConversationSessionItem[]>([]);
  const nextCursorRef = useRef<string | null>(null);
  const hasMoreRef = useRef(false);
  const requestInFlightRef = useRef(false);
  const requestVersionRef = useRef(0);

  const applySessionPage = useCallback(
    (
      page: Awaited<ReturnType<typeof conversationHistoryApi.listSessions>>,
      nextSessions: ConversationSessionItem[]
    ) => {
      sessionsRef.current = nextSessions;
      setRecentSessions(nextSessions);
      nextCursorRef.current = page?.nextCursor || null;
      hasMoreRef.current = Boolean(page?.hasMore && nextCursorRef.current);
      setRecentSessionsHasMore(hasMoreRef.current);
    },
    []
  );

  const refreshRecentSessions = useCallback((enabled = true) => {
    // 首页未进入会话态时跳过请求；失败只记录日志，不能阻断首页展示。
    if (!enabled) {
      return Promise.resolve([] as ConversationSessionItem[]);
    }
    const requestVersion = ++requestVersionRef.current;
    requestInFlightRef.current = true;
    setRecentSessionsLoading(true);
    setRecentSessionsLoadingMore(false);
    return conversationHistoryApi
      .listSessions({ limit: RECENT_SESSION_PAGE_SIZE })
      .then((page) => {
        if (requestVersion !== requestVersionRef.current) {
          return sessionsRef.current;
        }
        const nextSessions = page?.sessions || [];
        applySessionPage(page, nextSessions);
        return nextSessions;
      })
      .catch((error) => {
        console.error("加载近期会话失败", error);
        return sessionsRef.current;
      })
      .finally(() => {
        if (requestVersion === requestVersionRef.current) {
          requestInFlightRef.current = false;
          setRecentSessionsLoading(false);
        }
      });
  }, [applySessionPage]);

  const loadMoreRecentSessions = useCallback(() => {
    const after = nextCursorRef.current;
    if (requestInFlightRef.current || !hasMoreRef.current || !after) {
      return Promise.resolve(sessionsRef.current);
    }

    requestInFlightRef.current = true;
    const requestVersion = requestVersionRef.current;
    setRecentSessionsLoadingMore(true);
    return conversationHistoryApi
      .listSessions({
        limit: RECENT_SESSION_PAGE_SIZE,
        after,
      })
      .then((page) => {
        if (
          requestVersion !== requestVersionRef.current ||
          after !== nextCursorRef.current
        ) {
          return sessionsRef.current;
        }

        const merged = new Map(
          sessionsRef.current.map((session) => [session.sessionId, session])
        );
        (page?.sessions || []).forEach((session) => {
          if (session.sessionId && !merged.has(session.sessionId)) {
            merged.set(session.sessionId, session);
          }
        });
        const nextSessions = Array.from(merged.values());
        applySessionPage(page, nextSessions);
        return nextSessions;
      })
      .catch((error) => {
        console.error("加载更多近期会话失败", error);
        return sessionsRef.current;
      })
      .finally(() => {
        if (requestVersion === requestVersionRef.current) {
          requestInFlightRef.current = false;
          setRecentSessionsLoadingMore(false);
        }
      });
  }, [applySessionPage]);

  return {
    recentSessions,
    recentSessionsLoading,
    recentSessionsLoadingMore,
    recentSessionsHasMore,
    refreshRecentSessions,
    loadMoreRecentSessions,
  };
}
