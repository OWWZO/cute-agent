import api from "./index";

const DEFAULT_DEVICE_ID = "device-default";
const CONVERSATION_API_PREFIX = "/api/agent/conversation";
const SESSION_API_PREFIX = "/api/agent/session";

let runtimeDeviceId: string | null = DEFAULT_DEVICE_ID;

/**
 * 当前前端已经移除历史对话持久化，只保留一个轻量设备标识，
 * 兼容仍然需要该请求头的上传和流式接口。
 */
export function getDeviceId(): string {
  if (!runtimeDeviceId) {
    runtimeDeviceId = DEFAULT_DEVICE_ID;
  }
  return runtimeDeviceId;
}

export interface ConversationSessionItem {
  sessionId: string;
  title: string;
  status: string;
  /** conversation / subagent；会话树据此区分根与子节点 */
  sessionKind?: string | null;
  /** 父会话 ID；根会话为空 */
  parentSessionId?: string | null;
  /** 根会话 ID；根会话指向自己 */
  rootSessionId?: string | null;
  /** 子智能体类型；为空表示未记录 */
  subAgentType?: string | null;
  /** 该会话下的子会话数量；列表用它决定是否显示展开入口 */
  subSessionCount?: number | null;
  /** 所属 IM 渠道绑定；非空表示来自 QQ 等渠道 */
  bindingId?: number | null;
  /** 渠道会话形态：C2C / GROUP；非渠道会话为空 */
  channelChatType?: string | null;
  /** 匿名访客短码（如「访客 a1b2」）；非渠道会话为空 */
  visitorLabel?: string | null;
  latestQueryText: string;
  runCount: number;
  finishedRunCount: number;
  failedRunCount: number;
  startedAt: string;
  lastActiveAt: string;
}

export interface ConversationSessionPage {
  sessions: ConversationSessionItem[];
  nextCursor?: string | null;
  hasMore: boolean;
}

/** 会话运行状态：Web 端轮询它来发现 QQ 侧新起的轮次。 */
export interface ConversationSessionStatus {
  sessionId: string;
  status: string;
  latestRequestId?: string | null;
  bindingId?: number | null;
}

export interface ConversationReplayFrame {
  reqId: string;
  status: string;
  finished: boolean;
  resultMap: {
    agentType?: string;
    multiAgent?: Record<string, unknown>;
    eventData?: MESSAGE.EventData;
  };
}

export interface ConversationContextUsage {
  max: number;
  promptTokens?: number;
}

export interface ConversationRunSummary {
  requestId: string;
  status: string;
  queryPreview?: string;
  finalSummaryPreview?: string;
  finalSummaryText?: string;
  entryAgent?: string;
  llmCallCount?: number;
  toolCallCount?: number;
  artifactCount?: number;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number | null;
  hasReplay: boolean;
}

export interface ConversationRunReplay {
  runUid?: string;
  requestId: string;
  sessionId?: string;
  entryAgent?: string;
  status: string;
  queryText?: string;
  finalSummaryText?: string;
  llmCallCount?: number;
  toolCallCount?: number;
  artifactCount?: number;
  errorCode?: string;
  errorMsg?: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number | null;
  contextUsage?: ConversationContextUsage;
  replayFrames: ConversationReplayFrame[];
}

export type ConversationSessionFile = MESSAGE.ArtifactReference;

export interface ConversationHistoryPage {
  sessionId: string;
  title: string;
  status: string;
  deepThink: boolean;
  /** 会话级模型标识；null/undefined 表示用户从未显式选择过。 */
  model?: string | null;
  /** 是否开启深度思考；null/undefined 表示未显式选择。 */
  thinking?: boolean | null;
  /** 思考强度（low/medium/high）；null/undefined 表示用默认。 */
  thinkingEffort?: string | null;
  runCount: number;
  finishedRunCount: number;
  failedRunCount: number;
  latestRequestId?: string;
  latestQueryPreview?: string;
  latestSummaryPreview?: string;
  startedAt?: string;
  lastActiveAt?: string;
  runs: ConversationRunSummary[];
  nextCursor?: string | null;
  hasMore?: boolean;
}

export type ConversationHistoryRunDetail = ConversationRunReplay;

/** 会话切换 bootstrap：一次返回恢复会话所需的全部数据。 */
export interface ConversationSessionBootstrap {
  detail: ConversationHistoryPage;
  files: ConversationSessionFile[];
  askUser: Record<string, unknown>[];
  desktopControl: Record<string, unknown>[];
  planApproval: Record<string, unknown>[];
  /** 末尾 turn 的 run 回放，key = requestId。 */
  replays: Record<string, ConversationRunReplay>;
  /** false 表示未内联（超预算或无可用回放），需走并行补拉。 */
  replaysIncluded: boolean;
}

/** 旧 rich detail 的类型别名，保留给已有 replay 测试和调用方。 */
export interface ConversationHistoryDetail
  extends Omit<ConversationHistoryPage, "runs"> {
  runs: ConversationHistoryRunDetail[];
}

export interface ConversationHistoryQuery {
  limit?: number;
  after?: string | null;
}

export type ConversationSessionQuery = ConversationHistoryQuery;

export const conversationHistoryApi = {
  listSessions: (params: ConversationSessionQuery = {}) =>
    api.get<ConversationSessionPage>(`${CONVERSATION_API_PREFIX}/sessions`, {
      limit: params.limit ?? 10,
      ...(params.after ? { after: params.after } : {}),
    }) as unknown as Promise<ConversationSessionPage>,
  getSessionDetail: (sessionId: string, params: ConversationHistoryQuery = {}) =>
    api.get<ConversationHistoryPage>(
      `${CONVERSATION_API_PREFIX}/sessions/${encodeURIComponent(sessionId)}`,
      params
    ) as unknown as Promise<ConversationHistoryPage>,
  getSessionFiles: (sessionId: string) =>
    api.get<ConversationSessionFile[]>(
      `${CONVERSATION_API_PREFIX}/sessions/${encodeURIComponent(sessionId)}/files`
    ) as unknown as Promise<ConversationSessionFile[]>,
  /**
   * 会话树：给树中任一节点，返回「根 + 全部后代」的扁平列表。
   * 层级由 parentSessionId 表达，由调用方组树。
   */
  getSessionTree: (sessionId: string, limit = 200) =>
    api.get<ConversationSessionItem[]>(
      `${CONVERSATION_API_PREFIX}/sessions/${encodeURIComponent(sessionId)}/tree`,
      { limit }
    ) as unknown as Promise<ConversationSessionItem[]>,
  /**
   * 在已有会话（含子会话）上发起新一轮 run。
   * 偏好四项不传即沿用会话已存值；传了就落库。
   */
  submitSessionQuery: (
    sessionId: string,
    body: {
      query: string;
      model?: string;
      thinking?: boolean;
      thinkingEffort?: string;
      deepThink?: number;
    }
  ) =>
    api.post<{ accepted: boolean; sessionId: string; requestId: string }>(
      `${SESSION_API_PREFIX}/${encodeURIComponent(sessionId)}/query`,
      body
    ) as unknown as Promise<{
      accepted: boolean;
      sessionId: string;
      requestId: string;
    }>,
  getSessionStatus: (sessionId: string) =>
    api.get<ConversationSessionStatus>(
      `${CONVERSATION_API_PREFIX}/sessions/${encodeURIComponent(sessionId)}/status`
    ) as unknown as Promise<ConversationSessionStatus>,
  getSessionBootstrap: (sessionId: string, params: ConversationHistoryQuery = {}) =>
    api.get<ConversationSessionBootstrap>(
      `${CONVERSATION_API_PREFIX}/sessions/${encodeURIComponent(sessionId)}/bootstrap`,
      params
    ) as unknown as Promise<ConversationSessionBootstrap>,
  getRunReplay: (requestId: string) =>
    api.get<ConversationRunReplay>(
      `${CONVERSATION_API_PREFIX}/runs/${encodeURIComponent(requestId)}/replay`
    ) as unknown as Promise<ConversationRunReplay>,
};
