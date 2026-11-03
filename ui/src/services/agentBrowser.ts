import api from "./index";

const AGENT_BROWSER_API_PREFIX = "/api/agent/agent-browser";

export type AgentBrowserStatus = {
  exists: boolean;
  agentSessionId?: string;
  lastUsedAt?: string;
  reconstructed: boolean;
};

export type AgentBrowserLiveView = {
  browserLiveViewUrl: string;
  agentSessionId: string;
  reconstructed: boolean;
};

export const agentBrowserApi = {
  status: () => api.get<AgentBrowserStatus>(`${AGENT_BROWSER_API_PREFIX}/status`) as unknown as Promise<AgentBrowserStatus>,
  ensure: () => api.post<AgentBrowserLiveView>(`${AGENT_BROWSER_API_PREFIX}/ensure`) as unknown as Promise<AgentBrowserLiveView>,
  reset: () => api.delete<void>(`${AGENT_BROWSER_API_PREFIX}/session`) as unknown as Promise<void>,
};
