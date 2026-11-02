import { useEffect, useRef } from "react";
import { useStreamingText } from "@/hooks/useStreamingText";
import { cn } from "@/lib/utils";

type ToolArgStreamPreviewProps = {
  /** 后端累计入参（argumentsText），可能尚未形成合法 JSON */
  text: string;
  label?: string;
  streaming?: boolean;
  className?: string;
  /** 像终答一样占满可见区，不塞在折叠卡深处 */
  prominent?: boolean;
};

/** 距底部小于这个距离就继续跟随；用户手动上翻后不再抢滚动。 */
const AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 160;

/**
 * 工具入参流式展示：与终答同款 useStreamingText 逐字追赶 + 闪烁光标。
 */
export function ToolArgStreamPreview({
  text,
  label = "Arguments",
  streaming = true,
  className,
  prominent = true,
}: ToolArgStreamPreviewProps) {
  const scrollerRef = useRef<HTMLPreElement | null>(null);
  const autoScrollFrameRef = useRef(0);
  const displayed = useStreamingText(text || "", streaming);

  // 流式期间 displayed 每秒变几十次；同步读写 scroll 属性会强制布局回流，
  // 用 rAF 合并到每帧最多一次。
  useEffect(() => {
    if (!streaming) {
      return;
    }
    cancelAnimationFrame(autoScrollFrameRef.current);
    autoScrollFrameRef.current = requestAnimationFrame(() => {
      const el = scrollerRef.current;
      if (!el) {
        return;
      }
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
      if (distance < AUTO_SCROLL_BOTTOM_THRESHOLD_PX) {
        el.scrollTop = el.scrollHeight;
      }
    });
    return () => cancelAnimationFrame(autoScrollFrameRef.current);
  }, [displayed, streaming]);

  if (!displayed && !text && !streaming) {
    return null;
  }

  return (
    <div
      className={cn(
        "kimi-tool-stream-preview",
        prominent && "is-prominent",
        className
      )}
      data-testid="tool-arg-stream-preview"
    >
      {label ? (
        <div className="kimi-tool-stream-preview-label">
          {label}
          {streaming ? (
            <span className="kimi-tool-stream-preview-pulse" aria-hidden>
              ●
            </span>
          ) : null}
        </div>
      ) : null}
      <pre
        ref={scrollerRef}
        className="kimi-tool-stream-preview-body"
        aria-live="polite"
      >
        {/* 结束后以最终入参为准：追赶中的 displayed 可能还停在半截旧文本。 */}
        {streaming ? displayed : text}
        {streaming ? (
          <span className="kimi-tool-stream-preview-cursor" aria-hidden>
            ▍
          </span>
        ) : null}
      </pre>
    </div>
  );
}
