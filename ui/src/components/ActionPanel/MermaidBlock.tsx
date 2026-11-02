import React, { useEffect, useId, useRef, useState } from "react";
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

  useEffect(() => {
    let cancelled = false;
    setLoadError(false);
    const renderId = `mermaid-${reactId.replace(/[^a-zA-Z0-9_-]/g, "")}`;

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
  }, [code, reactId]);

  if (loadError) {
    return <pre className={cn("overflow-auto", className)}>{code}</pre>;
  }

  return (
    <div className={cn("mermaid", className)} ref={ref}>
      {code}
    </div>
  );
};

MermaidBlock.displayName = "MermaidBlock";

export default React.memo(MermaidBlock);
