import api from "./index";

// 子 Agent 管理 API：页面只组装定义和工具白名单，后端负责校验、持久化及运行时 reload。
export interface SubAgentDefinitionRecord {
  agentKey: string;
  displayName?: string;
  whenToUse: string;
  systemPrompt?: string;
  allowedTools?: string[];
  disallowedTools?: string[];
  toolPolicyMode?: "inherit" | "custom";
  deferredTools?: string[];
  maxSteps?: number | null;
  status?: number;
}

export type SubAgentDefinitionUpsertPayload =
  Omit<SubAgentDefinitionRecord, "systemPrompt"> & { systemPrompt: string };

const SUB_AGENT_DEFINITION_BASE = "/api/v1/admin/sub-agent-definitions";

export const subAgentDefinitionAdminApi = {
  queryList: () =>
    api.get<SubAgentDefinitionRecord[]>(
      `${SUB_AGENT_DEFINITION_BASE}/query-list`
    ) as unknown as Promise<SubAgentDefinitionRecord[]>,

  get: (agentKey: string) =>
    api.get<SubAgentDefinitionRecord>(
      `${SUB_AGENT_DEFINITION_BASE}/${encodeURIComponent(agentKey)}`
    ) as unknown as Promise<SubAgentDefinitionRecord>,

  toolCatalog: () =>
    api.get<string[]>(
      `${SUB_AGENT_DEFINITION_BASE}/tool-catalog`
    ) as unknown as Promise<string[]>,

  create: (payload: SubAgentDefinitionUpsertPayload) =>
    api.post<boolean>(
      `${SUB_AGENT_DEFINITION_BASE}/create`,
      payload
    ) as unknown as Promise<boolean>,

  update: (payload: SubAgentDefinitionUpsertPayload) =>
    api.put<boolean>(
      `${SUB_AGENT_DEFINITION_BASE}/update`,
      payload
    ) as unknown as Promise<boolean>,

  remove: (agentKey: string) =>
    api.delete<boolean>(
      `${SUB_AGENT_DEFINITION_BASE}/delete`,
      { agentKey }
    ) as unknown as Promise<boolean>,

  reload: () =>
    api.post<number>(
      `${SUB_AGENT_DEFINITION_BASE}/reload`
    ) as unknown as Promise<number>,
};
