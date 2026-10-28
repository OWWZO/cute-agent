import { useMemo, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import {
  parseLightweightDiffRows,
  type LightweightDiffLineParts,
} from "@/lib/patchPreview";

type LightweightDiffPreviewProps = {
  lines: readonly string[];
  showLineNumbers?: boolean;
  wrapLongLines?: boolean;
  fontSizePx?: number;
  /**
   * 自定义行内容渲染（用于叠加 shiki token）。返回 `undefined` 时回退到纯文本。
   * 注意：**不要**把 unified diff 的 `+/-/空格` 协议 marker 渲染成代码内容 ——
   * 增删只由行底色、3px 状态条和行号着色表达。
   */
  renderLineContent?: (line: LightweightDiffLineParts, index: number) => ReactNode;
  className?: string;
};

/**
 * 轻量 diff 预览（对齐 ZCode `components/ui/lightweight-diff-preview.tsx`）。
 *
 * 不使用 `@pierre/diffs`：内联挂在聊天消息里时，它的 Shadow DOM 汇总渲染会把
 * 主线程压住，展开即掉帧。这里只画行级底色 + 左侧状态条 + 行号 gutter，
 * 语法高亮由 `EditInlineDiffContent` 异步补 shiki token。
 */
export function LightweightDiffPreview({
  lines,
  showLineNumbers = true,
  wrapLongLines = true,
  fontSizePx = 12,
  renderLineContent,
  className,
}: LightweightDiffPreviewProps) {
  // lines 由上游 useMemo 稳定传入；这里再按引用缓存解析结果，
  // 父组件因高亮 token / 主题切换重渲染时不再重复解析全部行。
  const rows = useMemo(() => parseLightweightDiffRows(lines), [lines]);

  return (
    <div
      className={cn("kimi-diff-view", wrapLongLines ? "is-wrap" : "is-nowrap", className)}
      data-testid="inline-diff-preview"
    >
      <div className="kimi-diff-scroll" style={{ fontSize: fontSizePx }}>
        {rows.map((row, index) => {
          if (row.kind === "omitted") {
            return (
              <div className="kimi-diff-omitted" key={index}>
                已省略 {row.omittedLineCount} 行
              </div>
            );
          }

          const { parts } = row;
          return (
            <div className={cn("kimi-diff-row", `is-${parts.kind}`)} key={index}>
              {showLineNumbers ? (
                <span className="kimi-diff-gutter" aria-hidden="true">
                  {index + 1}
                </span>
              ) : null}
              <code className="kimi-diff-code">
                {renderLineContent?.(parts, index) ?? (parts.content || " ")}
              </code>
            </div>
          );
        })}
      </div>
    </div>
  );
}
