import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowUpIcon, LoaderCircle } from "lucide-react";

import { cn } from "@/lib/utils";
import ModelPicker from "@/components/GeneralInput/ModelPicker";
import { catalogApi, type CatalogModelRecord } from "@/services/catalog";
import { conversationHistoryApi } from "@/services/agentConversation";

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
  /** 提交成功后的回调：带上本轮提问与 requestId，供面板插入乐观轮并重开订阅 */
  onSubmitted?: (submitted: { query: string; requestId: string }) => void;
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

  const submit = useCallback(async () => {
    const text = query.trim();
    if (!text || submitting) {
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      // 面板在提交后可能立刻切走：只把 latest 会话的响应当有效，晚到的旧结果直接忽略。
      const accepted = await conversationHistoryApi.submitSessionQuery(sessionId, {
        query: text,
        // 未选模型时不下发该字段，后端沿用会话已存值
        ...(model ? { model } : {}),
      });
      if (latestSessionIdRef.current !== sessionId) {
        return;
      }
      setQuery("");
      onSubmitted?.({
        query: text,
        requestId: accepted.requestId
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
  }, [model, onSubmitted, query, sessionId, submitting]);

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
    </div>
  );
};

export default SubAgentSessionComposer;
