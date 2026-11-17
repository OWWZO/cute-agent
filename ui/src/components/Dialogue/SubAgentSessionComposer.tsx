import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowUpIcon, LoaderCircle } from "lucide-react";

import { cn } from "@/lib/utils";
import { getUniqId } from "@/utils";
import ModelPicker from "@/components/GeneralInput/ModelPicker";
import { catalogApi, type CatalogModelRecord } from "@/services/catalog";
import {
  AgentSessionQuerySubmitError,
  conversationHistoryApi,
  type AgentSessionQueryResult,
} from "@/services/agentConversation";
import type { AgentIngressMode } from "@/services/agentIngressProtocol";

/**
 * 子会话输入框：让用户在这个子会话里继续对话，并选择本轮模型。
 *
 * 模型偏好不写 sessionStorage：它属于会话本身，已随请求落库，
 * 再存一份浏览器副本只会在换设备时给出错误暗示。
 */
type Props = {
  sessionId: string;
  /** 会话库里已存的模型；为空表示用户还没在这个子会话选过 */
  sessionModel?: string | null;
  disabled?: boolean;
  /** 新 run 与中途指导分开回调，面板不再把指导误插成新的一轮。 */
  onSubmitted?: (submitted: SubAgentSubmission) => void;
};

export type SubAgentSubmission =
  | {
      kind: "new_run";
      query: string;
      requestId: string;
    }
  | {
      kind: "guidance";
      query: string;
      requestId: string;
      activeRequestId: string | null;
      mode: "injected" | "queued";
    };

const SubAgentSessionComposer = ({
  sessionId,
  sessionModel = null,
  disabled = false,
  onSubmitted,
}: Props) => {
  const [query, setQuery] = useState("");
  const [models, setModels] = useState<CatalogModelRecord[]>([]);
  const [model, setModel] = useState(sessionModel ?? "");
  const [modelMenuOpen, setModelMenuOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  // 会话切换时 submit 的异步结果可能晚到：用它过滤对旧 sessionId 的状态回写。
  const latestSessionIdRef = useRef(sessionId);
  latestSessionIdRef.current = sessionId;

  useEffect(() => {
    let cancelled = false;
    // 目录失败不阻断对话：空列表等于交给后端默认模型，但要留日志可排查。
    const loadModels = async () => {
      try {
        const list = await catalogApi.listModels();
        if (cancelled) {
          return;
        }
        const enabled = (Array.isArray(list) ? list : []).filter(
          (item) => (item.status ?? 1) === 1
        );
        setModels(enabled);
        setModel((prev) => {
          // 库值优先（用户上次在这个子会话用的模型），模型可能已下架，所以要校验；
          // 没有可用库值时退到目录第一个，与主输入框的选择语义一致。
          if (prev && enabled.some((item) => item.modelId === prev)) {
            return prev;
          }
          return enabled[0]?.modelId ?? "";
        });
      } catch (error) {
        if (cancelled) {
          return;
        }
        console.error("加载模型目录失败，子会话输入框退回默认模型", error);
        setModels([]);
      }
    };
    void loadModels();
    return () => {
      cancelled = true;
    };
  }, []);

  const canSubmit = useMemo(
    () => !disabled && !submitting && query.trim().length > 0,
    [disabled, submitting, query]
  );

  const submitWithRecovery = useCallback(
    async (text: string, requestId: string): Promise<AgentSessionQueryResult> => {
      const submitOnce = () =>
        conversationHistoryApi.submitSessionQuery(sessionId, {
          query: text,
          requestId,
          // 未选模型时不下发该字段，后端沿用会话已存值
          ...(model ? { model } : {}),
        });

      let failedRetries = 0;
      let oldFallbackUsed = false;
      while (true) {
        try {
          return await submitOnce();
        } catch (error) {
          if (
            error instanceof AgentSessionQuerySubmitError &&
            error.status === 503 &&
            error.mode === "inject_failed" &&
            error.retryable &&
            failedRetries < 1
          ) {
            failedRetries += 1;
            continue;
          }
          if (
            error instanceof AgentSessionQuerySubmitError &&
            error.concurrent &&
            error.activeRequestId &&
            (!error.activeSessionId || error.activeSessionId === sessionId) &&
            !oldFallbackUsed
          ) {
            oldFallbackUsed = true;
            const { agentRunApi } = await import("@/services/agentRun");
            const injected = await agentRunApi.inject({
              sessionId,
              requestId: error.activeRequestId,
              text,
              clientMessageId: requestId,
            });
            const mode = readInjectMode(injected?.mode);
            if (mode === "new_run") {
              continue;
            }
            if (injected?.accepted === true) {
              const held = mode === "held" || injected?.held === true;
              return {
                accepted: true,
                sessionId,
                requestId,
                activeRequestId: error.activeRequestId,
                mode: held ? "held" : mode ?? "injected",
              };
            }
          }
          throw error;
        }
      }
    },
    [model, sessionId]
  );

  const submit = useCallback(async () => {
    const text = query.trim();
    if (!text || submitting) {
      return;
    }
    setSubmitting(true);
    setError("");
    setNotice("");
    const requestId = getUniqId();
    try {
      // 面板在提交后可能立刻切走：只把 latest 会话的响应当有效，晚到的旧结果直接忽略。
      const accepted = await submitWithRecovery(text, requestId);
      if (latestSessionIdRef.current !== sessionId) {
        return;
      }
      setQuery("");
      if (accepted.mode === "held") {
        setNotice("消息已保留，需你确认后继续");
        return;
      }
      if (accepted.mode === "injected" || accepted.mode === "queued") {
        onSubmitted?.({
          kind: "guidance",
          query: text,
          requestId,
          activeRequestId: accepted.activeRequestId ?? null,
          mode: accepted.mode,
        });
        return;
      }
      onSubmitted?.({
        kind: "new_run",
        query: text,
        requestId: accepted.requestId || requestId,
      });
    } catch (err: unknown) {
      if (latestSessionIdRef.current !== sessionId) {
        return;
      }
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (latestSessionIdRef.current === sessionId) {
        setSubmitting(false);
      }
    }
  }, [onSubmitted, query, sessionId, submitWithRecovery, submitting]);

  return (
    <div className="flex flex-col gap-2">
      <div
        className={cn(
          "flex items-end gap-2 rounded-2xl border border-[var(--chat-border)] bg-[var(--chat-surface)] px-2.5 py-2",
          disabled && "opacity-60"
        )}
      >
        <textarea
          value={query}
          disabled={disabled || submitting}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            // Enter 发送，Shift+Enter 换行——与主输入框一致
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
          rows={1}
          placeholder="在这个子会话里继续对话…"
          className="max-h-32 min-h-8 flex-1 resize-none bg-transparent px-1 py-1 text-ui-base text-[var(--chat-text)] outline-none placeholder:text-[var(--chat-text-muted)]"
        />
        <ModelPicker
          models={models}
          value={model}
          onChange={setModel}
          disabled={disabled || models.length === 0}
          open={modelMenuOpen}
          onOpenChange={setModelMenuOpen}
          triggerClassName={(active, isDisabled) =>
            cn(
              "inline-flex h-8 max-w-[150px] items-center gap-1 rounded-md px-2 text-ui-sm transition-colors",
              "text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-muted)] hover:text-[var(--chat-text)]",
              active && "text-[var(--chat-text)]",
              isDisabled &&
                "cursor-not-allowed opacity-45 hover:bg-transparent hover:text-[var(--chat-text-soft)]"
            )
          }
        />
        <button
          type="button"
          disabled={!canSubmit}
          onClick={submit}
          aria-label="发送"
          className="inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-[var(--chat-text)] text-[var(--chat-surface)] transition-opacity disabled:opacity-30"
        >
          {submitting ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : (
            <ArrowUpIcon className="size-4" />
          )}
        </button>
      </div>
      {error ? (
        <div className="px-1 text-ui-caption text-[var(--color-danger)]">
          {error}
        </div>
      ) : null}
      {notice ? (
        <div className="px-1 text-ui-caption text-[var(--color-warning)]">
          {notice}
        </div>
      ) : null}
    </div>
  );
};

function readInjectMode(mode: unknown): AgentIngressMode | undefined {
  return mode === "new_run" ||
    mode === "injected" ||
    mode === "queued" ||
    mode === "held" ||
    mode === "inject_failed"
    ? mode
    : undefined;
}

export default SubAgentSessionComposer;
