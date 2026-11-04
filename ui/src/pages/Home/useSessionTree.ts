import { useCallback, useRef, useState } from "react";

import { conversationHistoryApi } from "@/services/agentConversation";
import {
  buildSessionTree,
  flattenSessionTree,
  type SessionTreeNode,
} from "@/utils/sessionTree";

/**
 * 侧栏会话树的展开状态与子会话加载。
 *
 * 侧栏列表本身只有主会话（后端白名单），子会话按需拉取：展开某个主会话时才请求
 * `/sessions/{id}/tree`。不做缓存——子会话是执行期产物，缓存只会让展开后看到过期结果，
 * 一次点击一次请求反而更简单也更新鲜。
 */
export function toggleExpandedSessionId(previous: ReadonlySet<string>, sessionId: string) {
  const next = new Set(previous);
  const expanding = !next.has(sessionId);
  if (expanding) {
    next.add(sessionId);
  } else {
    next.delete(sessionId);
  }
  return { next, expanding };
}

export function useSessionTree() {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const expandedIdsRef = useRef<Set<string>>(new Set());
  const [childrenByParent, setChildrenByParent] = useState<
    Record<string, SessionTreeNode[]>
  >({});
  const [loadingIds, setLoadingIds] = useState<Set<string>>(new Set());

  const toggle = useCallback((sessionId: string) => {
    const { next, expanding } = toggleExpandedSessionId(expandedIdsRef.current, sessionId);
    expandedIdsRef.current = next;
    setExpandedIds(next);
    if (!expanding) {
      return;
    }

    setLoadingIds((prev) => new Set(prev).add(sessionId));
    conversationHistoryApi
      .getSessionTree(sessionId)
      .then((rows) => {
        // 树接口返回「根 + 全部后代」，根自己已经在侧栏行里画过了，这里只要子节点。
        // 保留 depth 供缩进使用：将来开放嵌套时不必再改这一层。
        const { roots } = buildSessionTree(rows);
        const descendants = flattenSessionTree(roots).filter(
          (node) => node.session.sessionId !== sessionId
        );
        setChildrenByParent((prev) => ({
          ...prev,
          [sessionId]: descendants
        }));
      })
      .catch((error) => {
        console.error("加载子会话失败", error);
        setChildrenByParent((prev) => ({
          ...prev,
          [sessionId]: []
        }));
      })
      .finally(() => {
        setLoadingIds((prev) => {
          const next = new Set(prev);
          next.delete(sessionId);
          return next;
        });
      });
  }, []);

  return {
    isExpanded: (sessionId: string) => expandedIds.has(sessionId),
    isLoading: (sessionId: string) => loadingIds.has(sessionId),
    childrenOf: (sessionId: string): SessionTreeNode[] =>
      childrenByParent[sessionId] ?? [],
    toggle,
  };
}
