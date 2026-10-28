import { useCallback, useEffect, useRef, useState } from "react";

import type { MRagSessionDetail, MRagSessionSummary } from "./types";
import {
  createMragSession,
  getMragSessionDetail,
  listMragSessions,
} from "@/services/mragWorkspace";

export function useMragHistory(toolBaseUrl: string, selectedKnowledgeBaseId: string) {
  const [sessions, setSessions] = useState<MRagSessionSummary[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [sessionsError, setSessionsError] = useState("");
  const [activeSessionId, setActiveSessionId] = useState("");
  const [sessionDetail, setSessionDetail] = useState<MRagSessionDetail | null>(null);
  const [sessionDetailLoading, setSessionDetailLoading] = useState(false);

  const refreshSessions = useCallback(async () => {
    setSessionsLoading(true);
    setSessionsError("");
    try {
      const nextSessions = await listMragSessions(toolBaseUrl);
      setSessions(nextSessions);
      return nextSessions;
    } catch (error) {
      setSessionsError(error instanceof Error ? error.message : "历史加载失败");
      return [];
    } finally {
      setSessionsLoading(false);
    }
  }, [toolBaseUrl]);

  const loadSessionDetail = useCallback(async (sessionId: string) => {
    // 空 session 表示清空当前详情；有效 session 才更新 active id，避免列表切换产生悬挂状态。
    if (!sessionId) {
      setSessionDetail(null);
      return null;
    }
    setSessionDetailLoading(true);
    try {
      const detail = await getMragSessionDetail(toolBaseUrl, sessionId);
      setSessionDetail(detail);
      setActiveSessionId(sessionId);
      return detail;
    } finally {
      setSessionDetailLoading(false);
    }
  }, [toolBaseUrl]);

  const ensureActiveSession = useCallback(async () => {
    // 查询前懒创建会话，创建结果立即作为空详情写入，保证后续流式请求有稳定 sessionId。
    if (activeSessionId) {
      return activeSessionId;
    }

    const created = await createMragSession(toolBaseUrl, {
      kbId: selectedKnowledgeBaseId,
    });
    setActiveSessionId(created.sessionId);
    setSessionDetail({
      session: created,
      turns: [],
    });
    setSessions((previous) => [created, ...previous.filter((item) => item.sessionId !== created.sessionId)]);
    return created.sessionId;
  }, [activeSessionId, selectedKnowledgeBaseId, toolBaseUrl]);

  const createSession = useCallback(async () => {
    // 显式新建始终产生新 session，并置顶到本地历史列表，同时去掉同 ID 旧项。
    const created = await createMragSession(toolBaseUrl, {
      kbId: selectedKnowledgeBaseId,
    });
    setActiveSessionId(created.sessionId);
    setSessionDetail({
      session: created,
      turns: [],
    });
    setSessions((previous) => [created, ...previous.filter((item) => item.sessionId !== created.sessionId)]);
    return created;
  }, [selectedKnowledgeBaseId, toolBaseUrl]);

  // 自动打开最新会话只需要「当前有没有选中」这一个瞬时值；
  // 直接依赖 activeSessionId 会让每次切会话/建会话都重拉一次历史列表。
  const activeSessionIdRef = useRef(activeSessionId);
  useEffect(() => {
    activeSessionIdRef.current = activeSessionId;
  }, [activeSessionId]);

  useEffect(() => {
    // 首次加载历史后自动打开最新会话；已有 active id 时不抢占用户当前选择。
    void refreshSessions().then((nextSessions) => {
      if (!activeSessionIdRef.current && nextSessions[0]?.sessionId) {
        void loadSessionDetail(nextSessions[0].sessionId);
      }
    });
  }, [refreshSessions, loadSessionDetail]);

  useEffect(() => {
    // 知识库切换后旧会话不再适用，清空它让下一次查询重新建立正确关联。
    if (!selectedKnowledgeBaseId) {
      return;
    }
    if (sessionDetail?.session.coverKbId && sessionDetail.session.coverKbId !== selectedKnowledgeBaseId) {
      setActiveSessionId("");
      setSessionDetail(null);
    }
  }, [selectedKnowledgeBaseId, sessionDetail]);

  return {
    sessions,
    sessionsLoading,
    sessionsError,
    activeSessionId,
    sessionDetail,
    sessionDetailLoading,
    setActiveSessionId,
    refreshSessions,
    loadSessionDetail,
    ensureActiveSession,
    createSession,
  };
}
