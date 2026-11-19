import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEventHandler,
  type ReactNode,
} from "react";
import { BrainIcon, ChevronRightIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";

type ThinkingBlockProps = {
  text: string;
  streaming?: boolean;
  foldable?: boolean;
  label?: string;
  /** 已格式化好的时长文本（如 `0.1s`），原样展示。`durationMs` 优先于它。 */
  durationLabel?: string;
  /** 毫秒数：组件内拼成「持续了 X 秒」，与 ZCode 的 `chat.reasoning.durationSeconds` 同义。 */
  durationMs?: number;
  /** 标题行右侧额外控件（如版本切换），点击不触发折叠 */
  headerExtra?: ReactNode;
  /** 当前片段流式结束后是否自动收起；整轮 Agent 仍在运行时由调用方关闭。 */
  autoCollapseOnStreamEnd?: boolean;
  className?: string;
};

/**
 * 思考内容收起后不能立刻卸载 children：Radix 的高度动画需要 closed 阶段
 * 仍能读到真实内容高度（对齐 ZCode `REASONING_CONTENT_COLLAPSE_UNMOUNT_DELAY_MS`）。
 */
const COLLAPSE_UNMOUNT_DELAY_MS = 300;
/** 流式状态边界可能抖动；结束态稳定一小段时间后再收起，避免整块内容反复开合。 */
const STREAM_END_SETTLE_MS = 220;

function hasTextSelection(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  return Boolean(window.getSelection()?.toString());
}

export function isThinkingAtBottom(element: {
  scrollHeight: number;
  scrollTop: number;
  clientHeight: number;
}): boolean {
  return element.scrollHeight - element.scrollTop - element.clientHeight < 48;
}

/**
 * 流式思考块只需要「跟随最新内容」这一种自动滚动。
 * 正文高度变化时统一用 rAF 合并，避免每个 token 都同步改 scrollTop 造成画面抖动。
 */
function scheduleThinkingScrollToBottom(
  element: HTMLElement,
  frameRef: { current: number | null },
  shouldFollow: () => boolean
): void {
  if (frameRef.current !== null) {
    return;
  }
  frameRef.current = requestAnimationFrame(() => {
    frameRef.current = null;
    if (shouldFollow()) {
      element.scrollTop = element.scrollHeight;
    }
  });
}

/** 收起 + 流式时，标题右侧滚动显示思考的最后一行（图3 的核心观感）。 */
export function resolveStreamingSummary(text: string): string | undefined {
  // 只展示已经换行结束的完整行。若拿正在增长的末行做摘要，右侧文本会每个
  // token 都重新排版，折叠态看起来像闪烁/重叠。
  const lastBreak = text.lastIndexOf("\n");
  if (lastBreak <= 0) {
    return undefined;
  }
  const completedLines = text.slice(0, lastBreak).split("\n");
  for (let index = completedLines.length - 1; index >= 0; index -= 1) {
    const line = completedLines[index].trim();
    if (line) {
      return line;
    }
  }
  return undefined;
}

/**
 * 时长文案：数字在调用处统一换算成整秒；这里只处理已格式化文本和结束态兜底。
 */
function resolveDurationText(
  durationLabel: string | undefined,
  streaming: boolean
): string | undefined {
  if (durationLabel) {
    return durationLabel;
  }
  if (!streaming) {
    return "持续了几秒";
  }
  return undefined;
}

export const ThinkingBlock = memo(function ThinkingBlock({
  text,
  streaming = false,
  foldable = true,
  label = "思考",
  durationLabel,
  durationMs,
  headerExtra,
  autoCollapseOnStreamEnd = true,
  className,
}: ThinkingBlockProps) {
  const bodyRef = useRef<HTMLPreElement | null>(null);
  const scrollFrameRef = useRef<number | null>(null);
  const streamEndTimerRef = useRef<number | null>(null);
  const stickToBottomRef = useRef(true);
  const wasStreamingRef = useRef(streaming);
  /** 用户手动点过折叠后，流式过程中不再被强制拉开 */
  const userToggledRef = useRef(false);
  const isFoldable = foldable && Boolean(text.trim());
  const [open, setOpen] = useState(() => Boolean(streaming));
  const [shouldRenderContent, setShouldRenderContent] = useState(() =>
    Boolean(streaming)
  );

  useEffect(() => {
    const wasStreaming = wasStreamingRef.current;
    wasStreamingRef.current = streaming;
    if (streaming) {
      if (streamEndTimerRef.current !== null) {
        window.clearTimeout(streamEndTimerRef.current);
        streamEndTimerRef.current = null;
      }
      if (!wasStreaming) {
        stickToBottomRef.current = true;
        if (!userToggledRef.current) {
          setOpen(true);
        }
      }
      return;
    }
    if (!autoCollapseOnStreamEnd || userToggledRef.current) {
      if (streamEndTimerRef.current !== null) {
        window.clearTimeout(streamEndTimerRef.current);
        streamEndTimerRef.current = null;
      }
      return;
    }
    if (streamEndTimerRef.current !== null) {
      window.clearTimeout(streamEndTimerRef.current);
    }
    streamEndTimerRef.current = window.setTimeout(() => {
      streamEndTimerRef.current = null;
      setOpen(false);
    }, STREAM_END_SETTLE_MS);
  }, [autoCollapseOnStreamEnd, streaming, text]);

  useEffect(() => {
    return () => {
      if (streamEndTimerRef.current !== null) {
        window.clearTimeout(streamEndTimerRef.current);
        streamEndTimerRef.current = null;
      }
    };
  }, []);

  // 收起后延迟卸载：让收起动画先跑完，再拆掉重 DOM。
  useEffect(() => {
    if (open) {
      setShouldRenderContent(true);
      return;
    }
    if (!shouldRenderContent) {
      return;
    }
    const timer = window.setTimeout(
      () => setShouldRenderContent(false),
      COLLAPSE_UNMOUNT_DELAY_MS
    );
    return () => window.clearTimeout(timer);
  }, [open, shouldRenderContent]);

  useEffect(() => {
    const el = bodyRef.current;
    if (!el || !open) return;
    scheduleThinkingScrollToBottom(
      el,
      scrollFrameRef,
      () => stickToBottomRef.current
    );
    return () => {
      if (scrollFrameRef.current !== null) {
        cancelAnimationFrame(scrollFrameRef.current);
        scrollFrameRef.current = null;
      }
    };
  }, [text, open]);

  const handleBodyScroll = useCallback(() => {
    const el = bodyRef.current;
    if (el) {
      stickToBottomRef.current = isThinkingAtBottom(el);
    }
  }, []);

  /** Radix 负责 toggle；这里只拦「用户正在选中文本」这一种情况，避免打断复制。 */
  const handleOpenChange = useCallback((next: boolean) => {
    if (hasTextSelection()) {
      return;
    }
    if (streamEndTimerRef.current !== null) {
      window.clearTimeout(streamEndTimerRef.current);
      streamEndTimerRef.current = null;
    }
    userToggledRef.current = true;
    setOpen(next);
  }, []);

  /**
   * 只做冒泡拦截，**不要** `preventDefault`：
   * Radix 的 Slot 会把我们的 onClick 与它自己的 toggle handler 组合，
   * 部分版本的 `composeEventHandlers` 会检查 `defaultPrevented` 并跳过 toggle。
   * 折叠本身交给 Radix（`onOpenChange`），这里不重复 toggle。
   */
  const handleTriggerClick: MouseEventHandler = (event) => {
    event.stopPropagation();
  };

  const durationText = useMemo(() => {
    const seconds =
      typeof durationMs === "number" &&
      Number.isFinite(durationMs) &&
      durationMs >= 0
        ? Math.max(1, Math.ceil(durationMs / 1000))
        : undefined;
    return seconds === undefined
      ? resolveDurationText(durationLabel, streaming)
      : `持续了 ${seconds} 秒`;
  }, [durationMs, durationLabel, streaming]);
  const streamingSummary = useMemo(
    () => (!open && streaming ? resolveStreamingSummary(text) : undefined),
    [open, streaming, text]
  );

  if (!text.trim()) {
    return null;
  }

  if (!isFoldable) {
    return (
      <div className={cn("kimi-think", className)}>
        <div className="kimi-think-trigger" aria-hidden>
          <BrainIcon className="kimi-think-brain size-4 shrink-0" />
          <span
            className={cn(
              "kimi-think-label",
              streaming && "thinking-shimmer"
            )}
          >
            {label}
          </span>
          {durationText ? (
            <>
              <span className="kimi-think-dot" aria-hidden>
                ·
              </span>
              <span className="kimi-think-duration">{durationText}</span>
            </>
          ) : null}
        </div>
        <div className="kimi-think-indent">
          <pre ref={bodyRef} className="kimi-think-live">
            {text}
          </pre>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn("kimi-think", className)}
      data-open={open ? "true" : "false"}
      data-streaming={streaming ? "true" : "false"}
    >
      <Collapsible open={open} onOpenChange={handleOpenChange}>
        <CollapsibleTrigger asChild>
          <button
            type="button"
            className="kimi-think-trigger group/thinking"
            onClick={handleTriggerClick}
            aria-expanded={open}
            aria-label={open ? "折叠思考" : "展开思考"}
          >
            <BrainIcon className="kimi-think-brain size-4 shrink-0" />
            <span
              className={cn(
                "kimi-think-label",
                streaming && open && "thinking-shimmer"
              )}
            >
              {label}
            </span>
            {durationText ? (
              <>
                <span className="kimi-think-dot" aria-hidden>
                  ·
                </span>
                <span className="kimi-think-duration">{durationText}</span>
              </>
            ) : null}
            {streamingSummary ? (
              <span
                className="kimi-think-summary"
                data-testid="thinking-streaming-summary"
              >
                <span>{streamingSummary}</span>
              </span>
            ) : null}
            {headerExtra ? (
              <span
                className="kimi-think-extra"
                onClick={(event) => event.stopPropagation()}
                onKeyDown={(event) => event.stopPropagation()}
              >
                {headerExtra}
              </span>
            ) : null}
            <ChevronRightIcon
              className={cn(
                "kimi-think-chevron size-4 shrink-0 text-[var(--chat-text-faint)] transition-[transform,opacity] duration-200",
                open
                  ? "rotate-90 opacity-100"
                  : "rotate-0 opacity-0 group-hover/thinking:opacity-100"
              )}
            />
          </button>
        </CollapsibleTrigger>

        <CollapsibleContent forceMount={shouldRenderContent ? true : undefined}>
          {shouldRenderContent ? (
            <div className="kimi-think-indent pt-1">
              <pre
                ref={bodyRef}
                className="kimi-think-live"
                onScroll={handleBodyScroll}
                onClick={() => {
                  handleOpenChange(false);
                }}
              >
                {text}
              </pre>
            </div>
          ) : null}
        </CollapsibleContent>
      </Collapsible>
    </div>
  );
});
