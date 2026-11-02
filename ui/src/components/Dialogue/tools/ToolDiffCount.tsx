import { FlipMetricValue } from "./FlipMetricValue";

/** 单个 `+A` / `-B` 计数位。两侧结构完全一致，只有符号与配色不同。 */
function DiffCount({
  sign,
  value,
  className,
  animateInitial,
}: {
  sign: "+" | "-";
  value: number;
  className: string;
  animateInitial: boolean;
}) {
  const text = `${sign}${value}`;
  return (
    <span aria-label={text} className={className} role="text" title={text}>
      {sign}
      <FlipMetricValue value={String(value)} animateInitial={animateInitial} />
    </span>
  );
}

/**
 * 工具卡尾部 `+A −B` 变更计数（对齐 ZCode `renderers.tsx` 的 `renderDiffCount`）。
 *
 * - 数字走 `FlipMetricValue` 逐字符翻页，数值变化时只翻对应槽位；
 * - `+` 用新增色、`−` 用删除色，整体 font-mono + tabular-nums，避免位数变化时行宽抖动；
 * - 两侧计数为 0 时不渲染，保持与 ZCode 一致的「无变更就不占位」。
 */
export function ToolDiffCount({
  added,
  removed,
  animateInitial = false,
}: {
  added: number;
  removed: number;
  animateInitial?: boolean;
}) {
  if (added <= 0 && removed <= 0) {
    return null;
  }

  return (
    <span className="kimi-tool-diff">
      {added > 0 ? (
        <DiffCount
          sign="+"
          value={added}
          className="kimi-tool-diff-added"
          animateInitial={animateInitial}
        />
      ) : null}
      {removed > 0 ? (
        // ZCode `renderDiffCount` 用的是 ASCII 连字符 `-`，不是 U+2212
        <DiffCount
          sign="-"
          value={removed}
          className="kimi-tool-diff-removed"
          animateInitial={animateInitial}
        />
      ) : null}
    </span>
  );
}
