import api from "./index";
import { resolveServiceBaseUrl } from "@/utils/origin";
import { submitAcceptedCommand } from "./agentRun";

const customHost = resolveServiceBaseUrl(SERVICE_BASE_URL);
const ASK_USER_API_PREFIX = "/api/agent/ask-user";

export const ASK_USER_RESUME_URL = `${customHost}${ASK_USER_API_PREFIX}/resume`;

export type AskUserAnswerPayload = {
  questionId: string;
  answers: Record<string, string>;
};

export type AskUserAnswerResult = {
  questionId?: string;
  accepted?: boolean;
  idempotent?: boolean;
  resumeRequestId?: string;
  sessionId?: string;
  status?: string;
  message?: string;
};

export const ASK_USER_RESUME_EVENT = "cute-ask-user-resume";

export type AskUserResumeEventDetail = {
  resumeRequestId: string;
  sessionId?: string;
  questionId?: string;
  answers?: Record<string, string>;
};

export const askUserApi = {
  answer: (payload: AskUserAnswerPayload) =>
    api.post<AskUserAnswerResult>(`${ASK_USER_API_PREFIX}/answer`, payload) as unknown as Promise<AskUserAnswerResult>,

  pending: (sessionId: string) =>
    api.get<Record<string, unknown>[]>(`${ASK_USER_API_PREFIX}/pending`, {
      sessionId,
    }) as unknown as Promise<Record<string, unknown>[]>,

  cancel: (questionId: string) =>
    api.post<Record<string, unknown>>(`${ASK_USER_API_PREFIX}/cancel`, {
      questionId,
    }) as unknown as Promise<Record<string, unknown>>,
};

export function submitAskUserResume(resumeRequestId: string) {
  return submitAcceptedCommand(ASK_USER_RESUME_URL, { resumeRequestId });
}

export function dispatchAskUserResume(detail: AskUserResumeEventDetail) {
  if (typeof window === "undefined" || !detail?.resumeRequestId) {
    return;
  }
  window.dispatchEvent(new CustomEvent(ASK_USER_RESUME_EVENT, { detail }));
}
