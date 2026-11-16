import { useEffect, useRef, useState } from "react";
import { CheckIcon, CircleAlertIcon, CopyIcon } from "lucide-react";
import { copyText } from "@/utils";

/**
 * 工具统一失败卡。
 *
 * 失败后不再继续展开 Parameters / Result；用户首先需要看到的是「哪个工具失败了」
 * 和可复制的错误原文。所有工具渲染器共用这一张卡，避免 Fetch 失败后切回普通
 * 入参/出参界面，或不同工具出现不同的失败视觉。
 */
export function ToolFailureCard({
  name,
  text,
}: {
  name: string;
  text: string;
}) {
  const [copied, setCopied] = useState(false);
  const resetTimerRef = useRef<number | null>(null);
  const errorText = text.trim() || "工具执行失败，未返回错误详情。";

  useEffect(() => {
    return () => {
      if (resetTimerRef.current !== null) {
        window.clearTimeout(resetTimerRef.current);
      }
    };
  }, []);

  const handleCopy = () => {
    copyText(errorText);
    setCopied(true);
    if (resetTimerRef.current !== null) {
      window.clearTimeout(resetTimerRef.current);
    }
    resetTimerRef.current = window.setTimeout(() => {
      setCopied(false);
      resetTimerRef.current = null;
    }, 1500);
  };

  return (
    <div className="kimi-tool-failure" role="alert" data-testid="tool-failure">
      <div className="kimi-tool-failure-head">
        <span className="kimi-tool-failure-glyph" aria-hidden>
          <CircleAlertIcon />
        </span>
        <span className="kimi-tool-failure-name">{name}</span>
        <span className="kimi-tool-failure-status">执行失败</span>
        <button
          type="button"
          className="kimi-tool-failure-copy"
          aria-label={copied ? "已复制错误信息" : "复制错误信息"}
          title={copied ? "已复制" : "复制"}
          onClick={handleCopy}
        >
          {copied ? <CheckIcon /> : <CopyIcon />}
        </button>
      </div>
      <pre className="kimi-tool-failure-text">{errorText}</pre>
    </div>
  );
}
