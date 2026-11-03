import api from "./index";

export type CapabilityItem = {
  refId: string;
  name: string;
  enabled: boolean;
  source?: string;
};

export type SessionCapabilities = {
  locked: boolean;
  skills: CapabilityItem[];
  mcpServers: CapabilityItem[];
};

const sessionCapabilitiesPath = (sessionId: string) =>
  `/api/agent/session/${encodeURIComponent(sessionId)}/capabilities`;

export const sessionCapabilityApi = {
  get: (sessionId: string) =>
    api.get<SessionCapabilities>(sessionCapabilitiesPath(sessionId)) as unknown as Promise<SessionCapabilities>,

  setEnabled: (
    sessionId: string,
    kind: "skill" | "mcp",
    refId: string,
    enabled: boolean
  ) =>
    api.put<boolean>(
      sessionCapabilitiesPath(sessionId),
      { kind, refId, enabled }
    ) as unknown as Promise<boolean>,
};
