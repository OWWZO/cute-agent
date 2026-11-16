import { ToolJsonBlock, type ToolJsonValue } from "./ToolJsonBlock";

/**
 * 工具入参段。
 *
 * 段标题固定 `参数`，容器与出参段共用工具详情面，上限 240px。
 * 解析不出 JSON 时退化为等宽纯文本 —— ZCode 那边 `input` 已经是对象，
 * 而 Cute 拿到的是流式累积的 `argumentsText` 原始串，可能尚未成形。
 */
export function ToolParametersBlock({
  value,
  text,
}: {
  /** 已解析的 JSON 入参 */
  value?: ToolJsonValue;
  /** 原始入参文本（`value` 缺失时的兜底） */
  text?: string;
}) {
  const trimmedText = (text ?? "").trim();

  // 显式判 `undefined` / `null`：按真值判断会把 `0` / `false` / `""` 这类合法
  // 结果误当成「无入参」，静默退回原始文本。
  if ((value === undefined || value === null) && !trimmedText) {
    return null;
  }

  return (
    <section className="kimi-tool-section">
      <h4 className="kimi-tool-section-title">参数</h4>
      <div
        className="kimi-tool-detail kimi-tool-section-body"
        data-testid="tool-parameters"
      >
        {value !== undefined && value !== null ? (
          <ToolJsonBlock data={value} />
        ) : (
          <div className="kimi-tool-section-text">{trimmedText}</div>
        )}
      </div>
    </section>
  );
}
