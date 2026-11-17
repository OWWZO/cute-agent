import type { ConversationRunSummary } from "@/services/agentConversation";

const RESUME_REQUEST_ID_PREFIX = "resume_";
const ACTIVE_STATUSES = new Set(["RUNNING", "WAITING_INPUT"]);

export type ReviewTurn = {
  key: string;
  requestIds: string[];
  runs: ConversationRunSummary[];
  queryPreview: string;
  status: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs: number;
  llmCallCount: number;
  toolCallCount: number;
  artifactCount: number;
};

function normalizeStatus(value?: string | null) {
  return String(value || "UNKNOWN").trim().toUpperCase();
}

function isContinuationRun(run: ConversationRunSummary, hasCurrentTurn: boolean) {
  const requestId = String(run.requestId || "");
  if (requestId.startsWith(RESUME_REQUEST_ID_PREFIX)) {
    return true;
  }
  return hasCurrentTurn && !String(run.queryPreview || "").trim();
}

function buildTurn(runs: ConversationRunSummary[]): ReviewTurn {
  const first = runs[0];
  const last = runs[runs.length - 1];
  const queryPreview =
    runs
      .map((run) => String(run.queryPreview || "").trim())
      .find(Boolean) || "";
  return {
    key: String(first.requestId || ""),
    requestIds: runs
      .map((run) => String(run.requestId || ""))
      .filter(Boolean),
    runs,
    queryPreview,
    status: normalizeStatus(last?.status),
    startedAt: first?.startedAt,
    finishedAt: last?.finishedAt,
    durationMs: runs.reduce(
      (total, run) => total + (Number(run.durationMs) || 0),
      0
    ),
    llmCallCount: runs.reduce(
      (total, run) => total + (Number(run.llmCallCount) || 0),
      0
    ),
    toolCallCount: runs.reduce(
      (total, run) => total + (Number(run.toolCallCount) || 0),
      0
    ),
    artifactCount: runs.reduce(
      (total, run) => total + (Number(run.artifactCount) || 0),
      0
    ),
  };
}

/** Conversation history pages are ordered oldest-first. */
export function groupReviewTurns(
  runs: readonly ConversationRunSummary[]
): ReviewTurn[] {
  const turns: ConversationRunSummary[][] = [];
  for (const run of runs) {
    if (
      turns.length > 0 &&
      isContinuationRun(run, turns[turns.length - 1].length > 0)
    ) {
      turns[turns.length - 1].push(run);
      continue;
    }
    turns.push([run]);
  }
  return turns.map(buildTurn);
}

export function canReviewTurn(turn: ReviewTurn | null | undefined) {
  return Boolean(
    turn &&
      turn.requestIds.length > 0 &&
      turn.runs.every((run) => !ACTIVE_STATUSES.has(normalizeStatus(run.status)))
  );
}

export function findReviewTurn(
  turns: readonly ReviewTurn[],
  requestIds: readonly string[]
): ReviewTurn | null {
  if (!requestIds.length) return null;
  const target = new Set(requestIds);
  return (
    turns.find((turn) => turn.requestIds.some((requestId) => target.has(requestId))) ||
    null
  );
}

export function sameRequestIds(
  left: readonly string[],
  right: readonly string[]
) {
  if (left.length !== right.length) return false;
  const rightIds = new Set(right);
  return left.every((requestId) => rightIds.has(requestId));
}

export function mergeReviewRuns(
  current: readonly ConversationRunSummary[],
  incoming: readonly ConversationRunSummary[]
) {
  const merged = new Map<string, ConversationRunSummary>();
  for (const run of [...current, ...incoming]) {
    const requestId = String(run.requestId || "");
    if (requestId) merged.set(requestId, run);
  }
  return [...merged.values()];
}
