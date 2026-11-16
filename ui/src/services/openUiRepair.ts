import { getDeviceId } from "@/services/agentConversation";
import { getAccessToken } from "@/stores/auth";
import { resolveServiceBaseUrl } from "@/utils/origin";
import type { ReplaceBlock } from "@/lib/openui/repairIntent";

export type OpenUiFixStatus = "fixed" | "already_valid" | "fix_failed" | "disabled" | "error";

export interface OpenUiFixError {
  code?: string;
  message?: string;
  statementId?: string;
}

export interface OpenUiFixResponse {
  status: OpenUiFixStatus;
  content?: string | null;
  errors?: OpenUiFixError[];
  message?: string;
}

const OPEN_UI_REPAIR_PATH = "/api/agent/openui/repair";
const OPEN_UI_REPAIR_INTENTS_PATH = `${OPEN_UI_REPAIR_PATH}/intents`;
const OPEN_UI_REPAIR_TIMEOUT_MS = 95_000;
const OPEN_UI_INTENTS_TIMEOUT_MS = 5_000;

function resolveBaseUrl() {
  const configured = typeof SERVICE_BASE_URL === "string" ? SERVICE_BASE_URL : "";
  return resolveServiceBaseUrl(configured);
}

type OpenUiRequestFailure = {
  status: "error";
  message: string;
};

function requestSignal(signal: AbortSignal | undefined, timeoutMs: number) {
  const controller = new AbortController();
  const abortFromCaller = () => controller.abort(signal?.reason);
  if (signal?.aborted) {
    abortFromCaller();
  } else {
    signal?.addEventListener("abort", abortFromCaller, { once: true });
  }
  const timer = setTimeout(() => controller.abort(new Error("timeout")), timeoutMs);
  return {
    signal: controller.signal,
    cleanup: () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abortFromCaller);
    }
  };
}

async function postOpenUiRepair<T>(
  body: unknown,
  signal?: AbortSignal
): Promise<T | OpenUiRequestFailure> {
  const headers = authHeaders();
  const request = requestSignal(signal, OPEN_UI_REPAIR_TIMEOUT_MS);
  try {
    const response = await fetch(`${resolveBaseUrl()}${OPEN_UI_REPAIR_PATH}`, {
      method: "POST",
      credentials: "include",
      headers,
      body: JSON.stringify(body),
      signal: request.signal,
    });
    if (!response.ok) {
      return {
        status: "error",
        message: `HTTP ${response.status}`
      };
    }
    return (await response.json()) as T;
  } catch (error) {
    return {
      status: "error",
      message: request.signal.aborted && !signal?.aborted
        ? "修复请求超时"
        : error instanceof Error ? error.message : String(error)
    };
  } finally {
    request.cleanup();
  }
}

/**
 * 把渲染不了的 openui-lang 程序交给后端修一次（Thesys Autofix，key 在服务端）。
 *
 * 修复是增强能力：任何失败都返回 status 由调用方降级，不抛异常打断渲染。
 */
export async function repairOpenUiProgram(
  program: string,
  options: { context?: { role: string; content: string }[]; signal?: AbortSignal } = {}
): Promise<OpenUiFixResponse> {
  const { context, signal } = options;
  // 鉴权走 Bearer（AuthenticationFilter 只认这个，不认 cookie）；修复是后台行为，
  // 不走 utils/request 的 axios 实例，避免失败时弹 toast 打断对话。
  return postOpenUiRepair<OpenUiFixResponse>(
    {
      program,
      context: context ?? []
    },
    signal
  );
}

/** 意图修复（plans/038）：后端只透传模型给的 SEARCH/REPLACE 块，执行与验收都在前端。 */
export type OpenUiIntentStatus = "intent_ok" | "intent_empty" | "disabled" | "error";

export interface OpenUiIntentBlock {
  searchText: string;
  replaceText: string;
}

export interface OpenUiIntentFeedback {
  /** 上一轮被作废的块与原因，随重试请求带回给模型 */
  invalidBlocks?: { index: number; searchText: string; reason: string }[];
  /** 上一轮应用后的 parse 错误（前 5 条） */
  parseErrors?: { code?: string; message?: string }[];
}

export interface OpenUiIntentResponse {
  status: OpenUiIntentStatus;
  blocks?: OpenUiIntentBlock[];
  model?: string;
  message?: string;
}

/**
 * 请求一次意图修复。诊断信息随程序一起上送；feedback 是上一轮失败摘要（重试时带）。
 * 失败一律返回 status 由调用方降级，不抛异常。
 */
export async function repairOpenUiProgramWithIntent(
  program: string,
  options: {
    diagnosis: { incomplete: boolean; errors: { code?: string; message?: string }[] };
    feedback?: OpenUiIntentFeedback;
    signal?: AbortSignal;
  }
): Promise<OpenUiIntentResponse> {
  const { diagnosis, feedback, signal } = options;
  return postOpenUiRepair<OpenUiIntentResponse>(
    {
      program,
      mode: "intent",
      diagnosis,
      feedback: feedback ?? null
    },
    signal
  );
}

/** 意图重放 key：sessionId + messageId(requestId) + programHash。messageId 沿用会话里的 requestId。 */
export interface OpenUiRepairIntentKey {
  sessionId: string;
  messageId: string;
  programHash: string;
}

function authHeaders(): Record<string, string> {
  const token = getAccessToken();
  return {
    "Content-Type": "application/json",
    "X-Device-Id": getDeviceId(),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

/** 通过验收的意图落库（fire-and-forget，失败不影响渲染）。 */
export async function saveOpenUiRepairIntents(
  key: OpenUiRepairIntentKey,
  blocks: ReplaceBlock[],
  model?: string
): Promise<boolean> {
  try {
    const response = await fetch(`${resolveBaseUrl()}${OPEN_UI_REPAIR_INTENTS_PATH}`, {
      method: "POST",
      credentials: "include",
      headers: authHeaders(),
      body: JSON.stringify({
        ...key,
        blocks,
        model: model ?? null
      }),
    });
    return response.ok;
  } catch {
    return false;
  }
}

/** 重放：按 key 拉全部意图（后端按 intent_index 升序返回）。拿不到返回 null。 */
export async function fetchOpenUiRepairIntents(
  sessionId: string,
  messageId: string,
  programHash: string,
  signal?: AbortSignal
): Promise<(OpenUiIntentBlock & { intentIndex: number })[] | null> {
  const request = requestSignal(signal, OPEN_UI_INTENTS_TIMEOUT_MS);
  try {
    const query = new URLSearchParams({
      sessionId,
      messageId,
      programHash
    });
    const response = await fetch(`${resolveBaseUrl()}${OPEN_UI_REPAIR_INTENTS_PATH}?${query}`, {
      method: "GET",
      credentials: "include",
      headers: authHeaders(),
      signal: request.signal,
    });
    if (!response.ok) return null;
    const payload = (await response.json()) as { blocks?: (OpenUiIntentBlock & { intentIndex: number })[] };
    return payload.blocks ?? [];
  } catch {
    return null;
  } finally {
    request.cleanup();
  }
}

/** 原文规范化后的 SHA-256：重放前先校验 hash，不符说明原文已变，意图作废。 */
export async function computeProgramHash(program: string): Promise<string> {
  const normalized = program.trim();
  if (!crypto?.subtle) {
    // 非 https 环境（本地 http 调试）没有 crypto.subtle —— 用简易 FNV 兜底，保证功能可用。
    let hash = 0x811c9dc5;
    for (let i = 0; i < normalized.length; i++) {
      hash ^= normalized.charCodeAt(i);
      hash = Math.imul(hash, 0x01000193);
    }
    return `fnv-${(hash >>> 0).toString(16)}`;
  }
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(normalized));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
