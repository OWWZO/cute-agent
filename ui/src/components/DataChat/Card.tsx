import { useMemo } from "react";
import type { DataChatKpiItem } from "./chartConfig";

const EMPTY_KPI_LIST: DataChatKpiItem[] = [];

/** KPI 值可能是任意后端类型，渲染层只接受可安全展示的字符串/数字。 */
const toDisplayValue = (value: unknown): string | number =>
  typeof value === "string" || typeof value === "number" ? value : "—";

const Card: CuteType.FC<{ data: { kpiList?: DataChatKpiItem[] } }> = (props) => {
  const { data } = props;
  const kpiList = Array.isArray(data?.kpiList) ? data.kpiList : EMPTY_KPI_LIST;

  const items = useMemo(
    // showValue 优先于原始 value，统一成渲染层需要的 label/value/key 结构。
    () =>
      kpiList.map((item, index) => ({
        key: `kpi-${index}`,
        label: item.label || "未命名",
        value: toDisplayValue(item.showValue ?? item.value),
      })),
    [kpiList]
  );

  if (!items.length) {
    return (
      <div className="flex h-[120px] w-full items-center justify-center text-ui-caption text-[var(--chat-text-soft)]">
        暂无指标
      </div>
    );
  }

  return (
    <div className="grid w-full grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {items.map((item) => (
        <div
          key={item.key}
          className="rounded-xl border border-[var(--chat-border)]/60 bg-[var(--chat-surface-soft)]/40 px-4 py-3"
        >
          <div className="truncate text-ui-sm font-medium text-[var(--chat-text-soft)]">
            {item.label}
          </div>
          <div className="mt-1 truncate text-display-sm font-semibold tracking-[-0.02em] text-[var(--chat-text)] tabular-nums">
            {item.value}
          </div>
        </div>
      ))}
    </div>
  );
};

export default Card;
