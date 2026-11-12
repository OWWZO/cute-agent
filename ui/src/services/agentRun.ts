import api from "./index";
import { refreshAccessToken } from "./authTransport";
import { resolveServiceBaseUrl } from "@/utils/origin";
import { getDeviceId } from "@/services/agentConversation";
import { clearAuthSession, getAccessToken } from "@/stores/auth";
import { emitAuthSessionExpired } from "@/services/authSessionExpired";

const customHost = resolveServiceBaseUrl(SERVICE_BASE_URL);
const AGENT_RUN_API_PREFIX = "/api/agent/run";

function resolveTrustedOrigin(): string {
  const currentOrigin =
    typeof window === "undefined" ? "http://localhost" : window.location.origin;
  return new URL(customHost, currentOrigin).origin;
}

export function isTrustedAgentRunUrl(url: string): boolean {
  try {
    const trustedOrigin = resolveTrustedOrigin();
    return new URL(url, trustedOrigin).origin === trustedOrigin;
  } catch {
    return false;
  }
}

export const AGENT_QUERY_SUBMIT_URL = `${customHost}/web/api/v1/gpt/queryAgentStreamIncr`;

export function buildAgentSessionStreamUrl(params: {
  sessionId: string;
  lastEventSeq?: number;
}): string {
  const search = new URLSearchParams();
  search.set("lastEventSeq", String(params.lastEventSeq || 0));
  return `${customHost}/api/agent/session/${encodeURIComponent(params.sessionId)}/stream?${search.toString()}`;
}

export type AgentQuerySubmitResult = {
  accepted: boolean;
  sessionId: string;
  requestId: string;
};

export class AgentQuerySubmitError extends Error {
  status: number;
  concurrent: boolean;
  /** 409 时服务端正在执行的 run；用于把 Web 消息改投成指导。 */
  activeRequestId: string | null;
  activeSessionId: string | null;

  constructor(
    message: string,
    status: number,
    concurrent = false,
    details: {
      activeRequestId?: string | null;
      activeSessionId?: string | null;
    } = {}
  ) {
    super(message);
    this.name = "AgentQuerySubmitError";
    this.status = status;
    this.concurrent = concurrent;
    this.activeRequestId = details.activeRequestId ?? null;
    this.activeSessionId = details.activeSessionId ?? null;
  }
}

export async function submitAcceptedCommand(
  url: string,
  body: unknown
): Promise<AgentQuerySubmitResult> {
  if (!isTrustedAgentRunUrl(url)) {
    throw new Error("拒绝向非受信地址提交 Agent 请求");
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    "X-Device-Id": getDeviceId(),
  };
  const accessToken = getAccessToken();
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  let response = await fetch(url, {
    method: "POST",
    credentials: "include",
    headers,
    body: JSON.stringify(body),
  });

  if (response.status === 401) {
    try {
      const refreshedAccessToken = await refreshAccessToken();
      response = await fetch(url, {
        method: "POST",
        credentials: "include",
        headers: {
          ...headers,
          Authorization: `Bearer ${refreshedAccessToken}`,
        },
        body: JSON.stringify(body),
      });
    } catch {
      clearAuthSession();
      emitAuthSessionExpired();
    }
  }

  const payload = (await response.json().catch(() => null)) as {
    code?: string;
    info?: string;
    data?: AgentQuerySubmitResult & {
      activeRequestId?: string | null;
      activeSessionId?: string | null;
    };
  } | null;
  if (response.status === 409) {
    throw new AgentQuerySubmitError(
      payload?.info || "已有任务在进行中，请等待完成或先停止后再试",
      409,
      true,
      {
        activeRequestId: payload?.data?.activeRequestId,
        activeSessionId: payload?.data?.activeSessionId,
      }
    );
  }
  if (!response.ok) {
    throw new AgentQuerySubmitError(payload?.info || `请求失败，状态码: ${response.status}`, response.status);
  }
  if (!payload?.data?.accepted) {
    throw new AgentQuerySubmitError(payload?.info || "请求未被接受", response.status);
  }
  return payload.data;
}

export async function submitAgentQuery(body: unknown): Promise<AgentQuerySubmitResult> {
  return submitAcceptedCommand(AGENT_QUERY_SUBMIT_URL, body);
}

export const agentRunApi = {
  stop: (payload: { sessionId?: string; requestId: string }) =>
    api.post<Record<string, unknown>>(`${AGENT_RUN_API_PREFIX}/stop`, payload) as unknown as Promise<
      Record<string, unknown>
    >,
  inject: (payload: {
    sessionId?: string;
    requestId: string;
    text: string;
    clientMessageId?: string;
  }) =>
    api.post<Record<string, unknown>>(`${AGENT_RUN_API_PREFIX}/inject`, payload) as unknown as Promise<
      Record<string, unknown>
    >,
};
