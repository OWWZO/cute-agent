import type { ConversationSessionItem } from "@/services/agentConversation";

/**
 * 会话树节点：主会话在上，子会话挂在它下面。
 *
 * 后端返回的是扁平列表（同一 `rootSessionId` 下的全部行），层级由
 * `parentSessionId` 表达；这里只做一次「扁平 → 树」的纯变换。
 */
export type SessionTreeNode = {
  session: ConversationSessionItem;
  children: SessionTreeNode[];
  /** 缩进层级：根为 0，子会话为 1，嵌套子会话依次递增 */
  depth: number;
};

/**
 * 把扁平会话列表组成树。
 *
 * 保持后端返回的顺序（按创建时间升序），同一父下的子会话顺序因此稳定。
 * 父会话不在列表里的节点单独收在 `orphans` 里：静默丢弃会让用户看不到
 * 自己确实创建过的会话，但它们又挂不到任何父节点上。
 */
export function buildSessionTree(sessions: ConversationSessionItem[]): {
  roots: SessionTreeNode[];
  orphans: SessionTreeNode[];
} {
  const known = new Set(sessions.map((session) => session.sessionId));
  const byParent = new Map<string, ConversationSessionItem[]>();
  const orphans: ConversationSessionItem[] = [];

  sessions.forEach((session) => {
    if (!session.parentSessionId) {
      // 子会话却没有父 ID：数据异常，按孤儿处理而不是当成根，否则会混进主会话列表
      if (session.sessionKind === "subagent") {
        orphans.push(session);
      }
      return;
    }
    if (!known.has(session.parentSessionId)) {
      orphans.push(session);
      return;
    }
    const list = byParent.get(session.parentSessionId) ?? [];
    list.push(session);
    byParent.set(session.parentSessionId, list);
  });

  const buildNode = (
    session: ConversationSessionItem,
    depth: number,
    seen: Set<string>
  ): SessionTreeNode => {
    // 血缘成环时直接截断：树上不能出现无限递归，也不能因为一条脏数据打空整棵树
    if (seen.has(session.sessionId)) {
      return {
        session,
        children: [],
        depth
      };
    }
    seen.add(session.sessionId);
    const children = (byParent.get(session.sessionId) ?? []).map((child) =>
      buildNode(child, depth + 1, seen)
    );
    return {
      session,
      children,
      depth
    };
  };

  // 子会话即使没有父 ID 也不算根，否则会混进主会话列表
  const roots = sessions
    .filter((session) => !session.parentSessionId && session.sessionKind !== "subagent")
    .map((session) => buildNode(session, 0, new Set()));

  return {
    roots,
    orphans: orphans.map((session) => buildNode(session, 1, new Set())),
  };
}

/** 深度优先展开成可直接渲染的行，缩进由 depth 决定。 */
export function flattenSessionTree(nodes: SessionTreeNode[]): SessionTreeNode[] {
  const rows: SessionTreeNode[] = [];
  const walk = (node: SessionTreeNode) => {
    rows.push(node);
    node.children.forEach(walk);
  };
  nodes.forEach(walk);
  return rows;
}
