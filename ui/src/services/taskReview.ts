import { api } from "@/services";
import { getDeviceId } from "@/services/agentConversation";
import { getAccessToken } from "@/stores/auth";

const TASK_REVIEW_API = "/api/agent/task-review";
const EVAL_API = "/eval-api";

export type TaskReviewPreflight = {
  sessionId: string;
  requestIds: string[];
  title?: string | null;
  model?: string | null;
  canReview: boolean;
  blockers: string[];
  runs: Array<{
    requestId: string;
    status: string;
    agent?: string | null;
    createdAt?: string | null;
    startedAt?: string | null;
    finishedAt?: string | null;
    durationMs?: number | null;
    totalTokens?: number | null;
    llmCallCount?: number | null;
    toolCount?: number | null;
    subagentCount?: number | null;
    artifactCount?: number | null;
    errorCode?: string | null;
    errorMsg?: string | null;
  }>;
  aggregate: {
    durationMs: number;
    totalTokens: number;
    toolCount: number;
    failedToolCount: number;
    artifactCount: number;
    subagentCount: number;
  };
};

export type TaskReviewEvidence = {
  kind?: string;
  id?: string | null;
  path?: string | null;
  quote?: string;
};

export type TaskReviewFinding = {
  code: string;
  severity: "blocker" | "warning" | "info" | string;
  title: string;
  detail: string;
  evidenceRefs?: string[];
  evidence?: TaskReviewEvidence[];
  runRequestId?: string;
};

export type TaskReviewDimension = {
  id: string;
  label: string;
  score?: number;
  level: "high" | "medium" | "low" | "unknown" | string;
  passed?: boolean;
  reason: string;
  evidence?: TaskReviewEvidence[];
};

export type TaskReviewQuality = {
  status: "pending" | "running" | "verified" | "unverified" | "failed" | string;
  summary?: string;
  confidence?: number;
  judgeModel?: string | null;
  weightedScore?: number | null;
  dimensions?: TaskReviewDimension[];
  findings?: TaskReviewFinding[];
  suggestions?: Array<{ dimension?: string; title: string; detail: string }>;
  limitations?: string[];
  error?: { kind?: string; message?: string } | null;
  advisory?: boolean;
  completion?: TaskReviewCompletion;
};

export type TaskReviewCompletion = {
  conclusion: "completed" | "partial" | "incomplete" | "unknown" | string;
  basis: string;
  advisory: boolean;
  summary: string;
};

export type TaskReviewFastReport = {
  schemaVersion: string;
  subject: {
    sessionId?: string | null;
    requestIds: string[];
    runCount: number;
    criteria?: string | null;
  };
  execution: {
    status: string;
    durationMs?: number;
    totalTokens?: number;
    toolCount?: number;
    failedToolCount?: number;
    artifactCount?: number;
  };
  completion: TaskReviewCompletion;
  findings: TaskReviewFinding[];
  metrics: Record<string, unknown> & {
    runStatusCounts?: Record<string, number>;
    repeatedCallExcess?: number;
    riskScore?: number;
  };
  runs: Array<{
    requestId: string;
    status: string;
    finalText?: string;
    execution?: {
      status?: string;
      durationMs?: number | null;
      firstTokenMs?: number | null;
      totalTokens?: number | null;
    };
    completion?: TaskReviewCompletion;
    findings?: TaskReviewFinding[];
    timeline?: {
      steps?: Array<{
        id: string;
        ordinal: number;
        content: string;
        summary?: string;
        status?: string;
        rawStatus?: string;
        kind?: "model" | "tool" | string;
        visible?: boolean;
        toolCalls?: Array<{
          toolCallId?: string | null;
          toolName: string;
          argumentBrief?: string;
          argumentsPreview?: string;
          status: string;
          failed?: boolean;
          errorMsg?: string;
          durationMs?: number | null;
          resultPreview?: string;
        }>;
        evidence?: Array<{
          id: string;
          kind: string;
          sourceId?: string | null;
          status?: string | null;
          payload?: Record<string, unknown>;
        }>;
      }>;
      tools?: Array<{
        id: string;
        toolCallId?: string | null;
        toolName: string;
        status: string;
        failed?: boolean;
        errorMsg?: string;
        durationMs?: number | null;
        stepNo?: number | null;
        dispatchIndex?: number | null;
        llmInvocationId?: number | null;
        argumentBrief?: string;
        argumentsPreview?: string;
        inputPreview?: string;
        resultPreview?: string;
      }>;
    };
    artifacts?: Array<Record<string, unknown>>;
    limitations?: string[];
  }>;
  artifacts: Array<Record<string, unknown>>;
  limitations: string[];
};

export type TaskReviewJob = {
  schemaVersion: string;
  jobId: string;
  status: string;
  mode: string;
  includeLlmJudge: boolean;
  sessionId: string;
  requestIds: string[];
  title?: string | null;
  model?: string | null;
  createdAt: string;
  updatedAt: string;
  progress: Array<{ stage: string; status: string }>;
  fast: TaskReviewFastReport;
  quality: TaskReviewQuality;
  report?: Record<string, unknown> | null;
};

export async function getTaskReviewPreflight(
  sessionId: string,
  requestIds: string[]
) {
  return api.post<TaskReviewPreflight>(`${TASK_REVIEW_API}/preflight`, {
    sessionId,
    requestIds,
  }) as unknown as Promise<TaskReviewPreflight>;
}

export async function createTaskReviewJob(params: {
  sessionId: string;
  requestIds: string[];
  includeLlmJudge?: boolean;
  criteria?: string | null;
  clientRequestId?: string;
}) {
  return evalFetch<TaskReviewJob>("/task-review/jobs", {
    method: "POST",
    body: JSON.stringify({
      sessionId: params.sessionId,
      requestIds: params.requestIds,
      mode: "standard",
      includeLlmJudge: params.includeLlmJudge ?? true,
      criteria: params.criteria || null,
      clientRequestId:
        params.clientRequestId ||
        `task-review-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    }),
  });
}

export async function getTaskReviewJob(jobId: string) {
  return evalFetch<TaskReviewJob>(`/task-review/jobs/${encodeURIComponent(jobId)}`);
}

export async function listTaskReviewJobs(limit = 20) {
  return evalFetch<{ jobs: Array<Record<string, unknown>> }>(
    `/task-review/jobs?limit=${limit}`
  );
}

async function evalFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getAccessToken();
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  headers.set("X-Device-Id", getDeviceId());
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${EVAL_API}${path}`, {
    ...init,
    headers,
    credentials: "include",
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message =
      body?.error?.message ||
      body?.info ||
      body?.message ||
      `复盘服务请求失败（${response.status}）`;
    throw new Error(message);
  }
  return body as T;
}
