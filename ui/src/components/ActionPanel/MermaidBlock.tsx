import React, { useEffect, useId, useRef, useState } from "react";
import { TriangleAlert } from "lucide-react";
import { loadMermaid } from "@/lib/lazy/mermaid";
import { cn } from "@/lib/utils";

interface MermaidBlockProps {
  /** mermaid 源码文本。 */
  code: string;
  className?: string;
}

/**
 * Mermaid 图形渲染块。
 *
 * 从 `MarkdownRenderer` 里抽出，供 Markdown 预览与「代码预览遇到 mermaid 语言」共用。
 * 只对当前容器调用 `mermaid.render`，不再用全局 `contentLoaded` 扫描整页 ——
 * 页面同时存在多个图时，任一图更新不应触发其他图重渲染。
 */
const MermaidBlock: CuteType.FC<MermaidBlockProps> = (props) => {
  const { code, className } = props;
  const ref = useRef<HTMLDivElement | null>(null);
  const reactId = useId();
  const [loadError, setLoadError] = useState(false);
  // 重试计数：既触发 effect 重跑，也让 mermaid.render 每次用新的元素 id。
  const [retryToken, setRetryToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoadError(false);
    const renderId = `mermaid-${reactId.replace(/[^a-zA-Z0-9_-]/g, "")}-${retryToken}`;

    void loadMermaid()
      .then(async ({ default: mermaid }) => {
        const element = ref.current;
        if (cancelled || !element) return;
        const { svg, bindFunctions } = await mermaid.render(
          renderId,
          code,
          element
        );
        if (cancelled || ref.current !== element) return;
        element.innerHTML = svg;
        bindFunctions?.(element);
      })
      .catch((error) => {
        if (!cancelled) {
          console.error("加载 Mermaid 失败", error);
          setLoadError(true);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [code, reactId, retryToken]);

  if (loadError) {
    return (
      <div className={cn("flex flex-col gap-2", className)}>
        <div
          role="status"
          className="flex items-center gap-2 rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface-muted)] px-3 py-2 text-ui-sm text-[var(--chat-text-muted)]"
        >
          <TriangleAlert className="h-3.5 w-3.5 shrink-0" />
          <span className="min-w-0 flex-1">图形渲染失败，已回退为 Mermaid 源码。</span>
          <button
            type="button"
            className="shrink-0 underline hover:text-[var(--chat-text)]"
            onClick={() => setRetryToken((token) => token + 1)}
          >
            重试
          </button>
        </div>
        <pre className="overflow-auto">{code}</pre>
      </div>
    );
  }

  return (
    <div className={cn("mermaid", className)} ref={ref}>
      {code}
    </div>
  );
};

MermaidBlock.displayName = "MermaidBlock";

export default React.memo(MermaidBlock);
