/**
 * 流式/后台更新是否应写入当前主视图。
 * 会话切换后，非当前会话的更新只进本地缓存，不抢占界面。
 */
export function shouldApplyConversationToView(
  currentConversationId: string | undefined,
  nextConversationId: string | undefined
) {
  return Boolean(
    currentConversationId &&
      nextConversationId &&
      currentConversationId === nextConversationId
  );
}

export function deriveConversationMetaFromInput(
  info: Pick<CHAT.TInputInfo, "outputStyle" | "collaborationMode" | "agentKey">,
  params: {
    productType: string;
  }
) {
  const outputStyle = info.outputStyle || params.productType;
  const collaborationMode =
    outputStyle === "dataAgent" ? "normal" : info.collaborationMode;

  return {
    productType: outputStyle,
    collaborationMode,
    mainAgentKey:
      collaborationMode === "cluster" ? null : info.agentKey?.trim() || null,
  };
}

export function shouldHydrateConversationHistory(params: {
  conversation: CHAT.ConversationHistory;
  hydratedSessionIds: Set<string>;
}) {
  return Boolean(
    params.conversation.sessionId &&
      params.conversation.chatList.length === 0 &&
      params.conversation.dataChatList.length === 0 &&
      !params.hydratedSessionIds.has(params.conversation.sessionId)
  );
}

function isEmptyConversationDraft(conversation: CHAT.ConversationHistory) {
  return (
    conversation.chatList.length === 0 &&
    conversation.dataChatList.length === 0
  );
}

export function resolveLocalSessionSelection(
  localConversations: CHAT.ConversationHistory[],
  sessionId: string
) {
  const localConversation = localConversations.find(
    (conversation) => conversation.sessionId === sessionId
  );

  return {
    localConversation,
    shouldLoadRemote: !localConversation || !isEmptyConversationDraft(localConversation),
  };
}

/**
 * 已完成且本地时间戳不早于服务端最近活动时间时，完整历史快照可直接复用。
 * RUNNING 会话仍必须刷新，避免本地停在旧的运行态。
 */
export function canReuseCompletedSessionSnapshot(
  conversation: CHAT.ConversationHistory | undefined,
  session: CHAT.ConversationSessionItem
) {
  if (
    !conversation ||
    String(session.status).toUpperCase() === "RUNNING"
  ) {
    return false;
  }
  const lastActiveAt = Date.parse(session.lastActiveAt);
  return Number.isFinite(lastActiveAt) && conversation.updatedAt >= lastActiveAt;
}

export type ConversationDrafts = Record<string, string>;

export function getConversationDraft(
  drafts: ConversationDrafts,
  sessionId: string
) {
  return drafts[sessionId] || "";
}

export function setConversationDraft(
  drafts: ConversationDrafts,
  sessionId: string,
  draft: string
) {
  if (!sessionId || drafts[sessionId] === draft) {
    return drafts;
  }

  if (!draft) {
    const next = { ...drafts };
    delete next[sessionId];
    return next;
  }

  return {
    ...drafts,
    [sessionId]: draft,
  };
}

export type ConversationPendingFiles = Record<string, File[]>;

export function getConversationPendingFiles(
  pendingFiles: ConversationPendingFiles,
  sessionId: string
) {
  return pendingFiles[sessionId];
}

export function setConversationPendingFiles(
  pendingFiles: ConversationPendingFiles,
  sessionId: string,
  files: File[]
) {
  if (!sessionId) {
    return pendingFiles;
  }

  if (files.length === 0) {
    if (!Object.prototype.hasOwnProperty.call(pendingFiles, sessionId)) {
      return pendingFiles;
    }
    const next = { ...pendingFiles };
    delete next[sessionId];
    return next;
  }

  if (pendingFiles[sessionId] === files) {
    return pendingFiles;
  }

  return {
    ...pendingFiles,
    [sessionId]: files,
  };
}

function resolveConversationStatus(conversation: CHAT.ConversationHistory) {
  const latestChat = conversation.chatList[conversation.chatList.length - 1];
  if (latestChat?.metrics?.status) {
    return latestChat.metrics.status;
  }
  const latestDataChat =
    conversation.dataChatList[conversation.dataChatList.length - 1];
  if (latestDataChat?.error) {
    return "FAILED";
  }
  if (latestChat && !latestChat.loading) {
    return "SUCCESS";
  }
  if (latestDataChat && !latestDataChat.loading) {
    return "SUCCESS";
  }
  return "RUNNING";
}

function resolveLatestQueryText(conversation: CHAT.ConversationHistory) {
  const latestChat = conversation.chatList[conversation.chatList.length - 1];
  if (latestChat?.query) {
    return latestChat.query;
  }
  const latestDataChat =
    conversation.dataChatList[conversation.dataChatList.length - 1];
  return latestDataChat?.query || conversation.chatTitle || "";
}

/**
 * 将前端当前会话映射成侧边栏“最近”可渲染的数据结构。
 * 用户主动新建但尚未发送消息的会话也需要入口，避免切走后无法回到该界面。
 */
export function toRecentSessionItem(
  conversation: CHAT.ConversationHistory
): CHAT.ConversationSessionItem | null {
  if (!conversation.sessionId) {
    return null;
  }

  const startedAt = new Date(conversation.createdAt || Date.now()).toISOString();
  const lastActiveAt = new Date(
    conversation.updatedAt || conversation.createdAt || Date.now()
  ).toISOString();
  const failedRunCount = conversation.chatList.filter(
    (item) => item.metrics?.status === "FAILED"
  ).length;
  const finishedRunCount = conversation.chatList.filter(
    (item) => item.metrics?.status === "SUCCESS" || !item.loading
  ).length;

  return {
    sessionId: conversation.sessionId,
    title: conversation.chatTitle || conversation.title || "新对话",
    status: resolveConversationStatus(conversation),
    // 本地尚未落库的会话还没有任何子会话；不能把“未知”当成“有子会话”。
    subSessionCount: 0,
    latestQueryText: resolveLatestQueryText(conversation),
    runCount: conversation.chatList.length + conversation.dataChatList.length,
    finishedRunCount,
    failedRunCount,
    startedAt,
    lastActiveAt,
  };
}

/**
 * 本地会话优先展示，服务端刷新回来后按 sessionId 去重，避免同一会话出现两个按钮。
 */
export function mergeRecentSessions(
  remoteSessions: CHAT.ConversationSessionItem[],
  localSessions: CHAT.ConversationSessionItem[]
) {
  const merged = new Map<string, CHAT.ConversationSessionItem>();

  localSessions.forEach((session) => {
    if (session.sessionId) {
      merged.set(session.sessionId, session);
    }
  });
  remoteSessions.forEach((session) => {
    if (!session.sessionId) {
      return;
    }
    const local = merged.get(session.sessionId);
    if (!local) {
      merged.set(session.sessionId, session);
      return;
    }
    // 本地会话对象不携带服务端的树/渠道身份；只补结构字段，标题和运行态仍以本地为准。
    const remoteStructure = {
      ...(typeof session.subSessionCount === "number"
        ? { subSessionCount: session.subSessionCount }
        : {}),
      ...(session.sessionKind != null
        ? { sessionKind: session.sessionKind }
        : {}),
      ...(session.parentSessionId != null
        ? { parentSessionId: session.parentSessionId }
        : {}),
      ...(session.rootSessionId != null
        ? { rootSessionId: session.rootSessionId }
        : {}),
      ...(session.subAgentType != null
        ? { subAgentType: session.subAgentType }
        : {}),
    };
    if (session.bindingId != null) {
      merged.set(session.sessionId, {
        ...local,
        ...remoteStructure,
        bindingId: session.bindingId,
        channelChatType: session.channelChatType,
        visitorLabel: session.visitorLabel,
      });
    } else if (Object.keys(remoteStructure).length > 0) {
      merged.set(session.sessionId, {
        ...local,
        ...remoteStructure,
      });
    }
  });

  return Array.from(merged.values());
}

function conversationRunCount(conversation: CHAT.ConversationHistory) {
  return conversation.chatList.length + conversation.dataChatList.length;
}

const LOCAL_RECENT_SESSION_LIMIT = 20;

/**
 * 本地“新聊天”空草稿只保留一个；已有内容的会话不受影响。
 *
 * 置顶策略：
 * - 新会话 / 空草稿替换 → 置顶
 * - 新一轮 run（chatList/dataChatList 条数增加）→ 置顶
 * - 同轮流式增量 → 原地更新，避免双任务并发时侧边栏来回跳
 */
export function mergeLocalRecentConversations(
  currentLocalConversations: CHAT.ConversationHistory[],
  nextConversation: CHAT.ConversationHistory
) {
  const shouldReplaceEmptyDraft = isEmptyConversationDraft(nextConversation);
  const existingIndex = currentLocalConversations.findIndex(
    (item) => item.sessionId === nextConversation.sessionId
  );
  const existing =
    existingIndex >= 0 ? currentLocalConversations[existingIndex] : null;
  const runCountIncreased =
    !!existing &&
    conversationRunCount(nextConversation) > conversationRunCount(existing);
  const shouldPromote =
    existingIndex < 0 || shouldReplaceEmptyDraft || runCountIncreased;

  if (!shouldPromote && existingIndex >= 0) {
    const next = currentLocalConversations.slice();
    next[existingIndex] = nextConversation;
    return next;
  }

  return [
    nextConversation,
    ...currentLocalConversations.filter((item) => {
      if (item.sessionId === nextConversation.sessionId) {
        return false;
      }
      return !(shouldReplaceEmptyDraft && isEmptyConversationDraft(item));
    }),
  ].slice(0, LOCAL_RECENT_SESSION_LIMIT);
}
