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

export type TaskReviewEvidenceIndexEntry = {
  requestId: string;
  runId: string;
  toolCallId: string;
  toolName: string;
  step?: number | null;
  status: string;
  failed: boolean;
};

export type TaskReviewEvidenceDetail = {
  schemaVersion: string;
  ref: string;
  kind: "tool_call";
  resolved: true;
  requestId: string;
  runId: string;
  toolCallId: string;
  toolName: string;
  step?: number | null;
  status: string;
  failed: boolean;
  input: {
    present: boolean;
    raw: string;
    parsed: unknown;
    parseError?: string | null;
    truncated: boolean;
    originalLength: number;
  };
  output: {
    present: boolean;
    source: "toolResult" | "llmObservation" | "missing";
    raw: string;
    parsed: unknown;
    parseError?: string | null;
    truncated: boolean;
    originalLength: number;
  };
  error: {
    message: string;
    code?: string | null;
    class: string;
  };
  timing: {
    startedAt?: string | null;
    finishedAt?: string | null;
    durationMs?: number | null;
  };
  context: {
    subAgentId?: string | null;
    subAgentType?: string | null;
    dispatchIndex?: number | null;
    llmInvocationId?: number | string | null;
  };
  recovery: {
    recovered: boolean;
    type: string;
    summary: string;
    refs: string[];
  };
  artifacts: Array<{
    artifactId: string;
    relativeRef?: string | null;
    mimeType?: string | null;
    fileSize?: number | null;
    fileHash?: string | null;
    previewAvailable?: boolean | null;
  }>;
  limitations: string[];
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
  applicable?: boolean;
  notApplicableReason?: string;
  diagnosticRefs?: string[];
  reason: string;
  evidence?: TaskReviewEvidence[];
};

export type TaskReviewFailureLayer =
  | "MODEL_DECISION"
  | "MODEL_ARGUMENT"
  | "HARNESS_CONTRACT"
  | "HARNESS_RUNTIME"
  | "ENVIRONMENT"
  | "USER_ACTION"
  | "TRACE_GAP"
  | "UNKNOWN";

export type TaskReviewFailureAttribution = {
  attributionId: string;
  requestId: string;
  runId: string;
  toolCallId: string;
  step?: number | null;
  toolName: string;
  toolStatus: string;
  failureKind: string;
  primaryLayer: TaskReviewFailureLayer | string;
  contributingLayers: Array<TaskReviewFailureLayer | string>;
  reason: string;
  recovered: boolean;
  recoverySummary: string;
  confidence: number;
  source: "deterministic" | "judge" | "deterministic+judge" | string;
  ruleIds: string[];
  evidenceRefs: string[];
  evidence?: TaskReviewEvidence[];
};

export type TaskReviewDiagnosticSummary = {
  totalFailures: number;
  attributedFailures: number;
  unattributedFailures: number;
  layerCounts: Partial<Record<TaskReviewFailureLayer | string, number>>;
  recoveredCount: number;
  unrecoveredCount: number;
  parameterIssueCount: number;
  diagnosisMismatchCount: number;
};

export type TaskReviewDeveloperAction = {
  actionId: string;
  title: string;
  targetLayer: TaskReviewFailureLayer | string;
  priority: "P0" | "P1" | "P2" | string;
  actionScope:
    | "PRODUCT_RUNTIME"
    | "TOOL_IMPLEMENTATION"
    | "MODEL_POLICY"
    | "ENVIRONMENT_CONFIG"
    | "TRACE_INSTRUMENTATION"
    | "USER_WORKSPACE"
    | "NO_ACTION"
    | string;
  status: "ready" | "needs_more_evidence" | string;
  bugSignature: string;
  failureKind: string;
  toolName: string;
  frequency: {
    count: number;
    recovered: number;
    unrecovered: number;
  };
  problemDefinition: {
    observed: string;
    expected: string;
    impact: string;
  };
  evidenceRefs: string[];
  reproduction: {
    preconditions: string[];
    steps: string[];
    expectedFailure: string;
  };
  rootCauseHypothesis: {
    summary: string;
    confidence: number;
    alternatives: string[];
  };
  affectedAreas: Array<{ kind: string; name: string }>;
  fixPlan: Array<{ order: number; summary: string; detail: string }>;
  testPlan: {
    unit: string[];
    integration: string[];
    regression: string[];
  };
  acceptanceCriteria: string[];
  target: string;
  action: string;
  expectedEffect: string;
  agentPrompt: string;
};

export type TaskReviewNarrative = {
  schemaVersion: string;
  status: "verified" | "fact_only" | "failed" | string;
  headline: string;
  executiveSummary: string[];
  taskUnderstanding: {
    goal: string;
    constraints: string[];
    acceptanceCriteria: string[];
  };
  executionNarrative: Array<{
    phase: string;
    summary: string;
    outcome: string;
    evidenceRefs: string[];
  }>;
  toolStory: Array<{
    toolName: string;
    purpose: string;
    calls: number;
    failures: number;
    assessment: string;
  }>;
  failureStory: Array<{
    title: string;
    primaryLayer: string;
    detail: string;
    recovered: boolean;
    evidenceRefs: string[];
  }>;
  whatWorked: string[];
  whatFailed: string[];
  betterApproach: Array<{
    title: string;
    detail: string;
    tradeoff: string;
  }>;
  developerActions: TaskReviewDeveloperAction[];
  verification: {
    verified: string[];
    unverified: string[];
  };
  limitations: string[];
  markdown: string;
  error?: { kind?: string; message?: string } | null;
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
  rubricId?: string;
  failureAttributions?: TaskReviewFailureAttribution[];
  diagnosticSummary?: TaskReviewDiagnosticSummary;
  diagnostics?: {
    failureAttributions?: TaskReviewFailureAttribution[];
    diagnosticSummary?: TaskReviewDiagnosticSummary;
    toolContracts?: Array<Record<string, unknown>>;
  };
  narrative?: TaskReviewNarrative | null;
  narrativeStatus?: string;
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
        evidenceRef?: string;
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
  diagnostics?: {
    failureAttributions?: TaskReviewFailureAttribution[];
    diagnosticSummary?: TaskReviewDiagnosticSummary;
    toolContracts?: Array<Record<string, unknown>>;
  };
  evidenceIndex?: Record<string, TaskReviewEvidenceIndexEntry>;
  narrative?: TaskReviewNarrative | null;
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

export async function getTaskReviewEvidence(jobId: string, ref: string) {
  const query = new URLSearchParams({ ref });
  return evalFetch<TaskReviewEvidenceDetail>(
    `/task-review/jobs/${encodeURIComponent(jobId)}/evidence?${query.toString()}`
  );
}

export async function downloadTaskReviewReport(jobId: string) {
  const token = getAccessToken();
  const headers = new Headers();
  headers.set("X-Device-Id", getDeviceId());
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(
    `${EVAL_API}/task-review/jobs/${encodeURIComponent(jobId)}/report.md`,
    { headers, credentials: "include" }
  );
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(
      body?.error?.message || `下载复盘报告失败（${response.status}）`
    );
  }
  return response.blob();
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
    throw new TaskReviewApiError(message, {
      status: response.status,
      code: body?.error?.code,
      candidates: body?.error?.candidates,
    });
  }
  return body as T;
}

export class TaskReviewApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly candidates?: Array<Record<string, unknown>>;

  constructor(
    message: string,
    options: {
      status: number;
      code?: string;
      candidates?: Array<Record<string, unknown>>;
    }
  ) {
    super(message);
    this.name = "TaskReviewApiError";
    this.status = options.status;
    this.code = options.code;
    this.candidates = options.candidates;
  }
}
