import api from "./index";

export interface CatalogModelRecord {
  modelId: string;
  modelName: string;
  modelType?: string;
  supportsThinking?: number;
  contextWindow?: number | null;
  status?: number;
}

export interface CatalogSubAgentRecord {
  agentKey: string;
  displayName?: string;
  whenToUse: string;
  toolSummary?: string[];
  toolPolicyMode?: "inherit" | "custom";
  maxSteps?: number | null;
  status?: number;
}

export interface CatalogSkillRecord {
  name: string;
  description?: string | null;
  sourceSummary?: string | null;
  status?: number;
}

export interface CatalogMcpRecord {
  mcpId: string;
  mcpName: string;
  transportType: string;
  status?: number;
}

export interface CatalogCapabilitiesRecord {
  skills: CatalogSkillRecord[];
  mcps: CatalogMcpRecord[];
}

export interface CatalogModelCreatePayload {
  apiId?: string;
  baseUrl: string;
  apiKey: string;
  completionsPath?: string;
  embeddingsPath?: string;
  modelId: string;
  modelName: string;
  modelType?: string;
  modelUsage?: string;
  supportsThinking?: number;
  contextWindow?: number | null;
  status?: number;
}

export interface CatalogSubAgentCreatePayload {
  agentKey: string;
  displayName?: string;
  whenToUse: string;
  systemPrompt: string;
  allowedTools?: string[];
  disallowedTools?: string[];
  toolPolicyMode?: "inherit" | "custom";
  deferredTools?: string[];
  maxSteps?: number | null;
  status?: number;
}

export interface CatalogMcpCreatePayload {
  mcpId: string;
  mcpName: string;
  transportType: string;
  transportConfig?: string;
  requestTimeout?: number;
  status?: number;
}

export interface CatalogSkillPackagePreview {
  name: string;
  description?: string | null;
  contentPreview?: string;
  extraFiles?: string[];
  nameTaken?: boolean;
}

export interface CatalogSkillCreatePayload {
  name?: string;
  description?: string;
  content: string;
}

const CATALOG_BASE = "/api/v1/catalog";

export const catalogApi = {
  listModels: () =>
    api.get<CatalogModelRecord[]>(
      `${CATALOG_BASE}/models`
    ) as unknown as Promise<CatalogModelRecord[]>,

  createModel: (payload: CatalogModelCreatePayload) =>
    api.post<boolean>(`${CATALOG_BASE}/models`, payload) as unknown as Promise<boolean>,

  listSubAgents: () =>
    api.get<CatalogSubAgentRecord[]>(
      `${CATALOG_BASE}/sub-agents`
    ) as unknown as Promise<CatalogSubAgentRecord[]>,

  createSubAgent: (payload: CatalogSubAgentCreatePayload) =>
    api.post<boolean>(`${CATALOG_BASE}/sub-agents`, payload) as unknown as Promise<boolean>,

  listCapabilities: () =>
    api.get<CatalogCapabilitiesRecord>(
      `${CATALOG_BASE}/capabilities`
    ) as unknown as Promise<CatalogCapabilitiesRecord>,

  createMcp: (payload: CatalogMcpCreatePayload) =>
    api.post<boolean>(`${CATALOG_BASE}/mcps`, payload) as unknown as Promise<boolean>,

  parseSkillPackage: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return api.post<CatalogSkillPackagePreview>(
      `${CATALOG_BASE}/skills/parse-package`,
      form,
    ) as unknown as Promise<CatalogSkillPackagePreview>;
  },

  uploadSkill: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return api.post<CatalogSkillRecord>(
      `${CATALOG_BASE}/skills/upload`,
      form,
    ) as unknown as Promise<CatalogSkillRecord>;
  },

  createSkill: (payload: CatalogSkillCreatePayload) =>
    api.post<CatalogSkillRecord>(
      `${CATALOG_BASE}/skills/create`,
      payload,
    ) as unknown as Promise<CatalogSkillRecord>,

  importSkill: (url: string) =>
    api.post<CatalogSkillRecord>(
      `${CATALOG_BASE}/skills/import-url`,
      { url },
    ) as unknown as Promise<CatalogSkillRecord>,
};
