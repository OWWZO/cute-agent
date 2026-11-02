import Chart from "./Chart";
import SimpleTable from "./SimpleTable";
import Card from "./Card";
import { cn } from "@/lib/utils";
import { memo, useState, useMemo } from "react";
import {
  buildChartConfig,
  resolveCurrentChartType,
  type DataChatSourceConfig,
} from "./chartConfig";
import { defaultChartPresets } from "./chartPresets";
import { buildQuerySummary } from "./querySummary";
import {
  BarChart3,
  ChartLine,
  ChartPie,
  ChevronDown,
  ChevronUp,
  Table2,
} from "lucide-react";

const CHART_TYPES = [
  {
    type: "line",
    icon: ChartLine,
    label: "折线",
  },
  {
    type: "bar",
    icon: BarChart3,
    label: "柱状",
  },
  {
    type: "hbar",
    icon: BarChart3,
    label: "条形",
  },
  {
    type: "pie",
    icon: ChartPie,
    label: "饼图",
  },
  {
    type: "table",
    icon: Table2,
    label: "表格",
  },
] as const;

const CHIP_CLASS = "rounded-md px-2 py-0.5";

/**
 * 分析参数标签。配色属于 DataChat 的数据编码色板（该目录在 check:tokens 中豁免），
 * 卡片本身固定白底，因此不跟随暗色主题的界面 token 切换。
 */
function renderChips(label: string, items: string[], toneClass: string) {
  if (!items.length) {
    return null;
  }
  return (
    <div className="flex items-baseline gap-2">
      <span className="w-8 shrink-0 whitespace-nowrap">{label}</span>
      <div className="flex flex-wrap gap-1.5">
        {items.map((item, i) => (
          <span key={i} className={cn(CHIP_CLASS, toneClass)}>
            {item}
          </span>
        ))}
      </div>
    </div>
  );
}

const TypeBar: CuteType.FC<{
  currentType: string;
  chartCfg: DataChatSourceConfig;
  onChange?: (val: string) => void;
}> = (props) => {
  const { currentType, chartCfg, onChange } = props;
  const [showQueryArgs, setShowQueryArgs] = useState(true);
  // 查询摘要和图表类型切换都只依赖当前配置，不重新修改后端返回的数据源。
  const summary = useMemo(() => buildQuerySummary(chartCfg), [chartCfg]);

  return (
    <>
      <div className="mb-3 flex w-full flex-wrap items-center justify-start gap-2">
        {summary.showTypeSwitch ? (
          <div className="flex items-center gap-0.5 rounded-lg border border-[var(--chat-border)]/70 bg-[var(--chat-surface-soft)]/60 p-0.5">
            {CHART_TYPES.map((item) => {
              const Icon = item.icon;
              const active = currentType === item.type;
              return (
                <button
                  key={item.type}
                  type="button"
                  title={item.label}
                  className={cn(
                    "inline-flex h-8 items-center gap-1 rounded-md px-2.5 text-ui-sm transition-colors",
                    active
                      ? "bg-white font-medium text-[var(--chat-text)] shadow-sm"
                      : "text-[var(--chat-text-soft)] hover:bg-white/70 hover:text-[var(--chat-text)]"
                  )}
                  onClick={() => onChange?.(item.type)}
                >
                  <Icon className="h-3.5 w-3.5" />
                  <span className="hidden sm:inline">{item.label}</span>
                </button>
              );
            })}
          </div>
        ) : null}
        <button
          type="button"
          className="inline-flex h-8 items-center gap-1 rounded-lg border border-[var(--chat-border)]/70 px-3 text-ui-sm text-[var(--chat-text-soft)] transition-colors hover:bg-[var(--chat-surface-soft)] hover:text-[var(--chat-text)]"
          onClick={() => setShowQueryArgs(!showQueryArgs)}
        >
          <span>分析参数</span>
          {showQueryArgs ? (
            <ChevronUp className="h-3.5 w-3.5" />
          ) : (
            <ChevronDown className="h-3.5 w-3.5" />
          )}
        </button>
      </div>
      {showQueryArgs ? (
        <div className="mb-3 flex w-full flex-col gap-2 text-ui-sm leading-6 text-[var(--chat-text-soft)]">
          {renderChips("维度", summary.dims, "bg-[#edeffd] text-[#4a5fe8]")}
          {renderChips("指标", summary.measures, "bg-[#eaf8ec] text-[#2fbc44]")}
          {renderChips("筛选", summary.filters, "bg-[#f2eafe] text-[#8031f5]")}
          {summary.formula ? (
            <div className="flex items-baseline gap-2">
              <span className="w-8 shrink-0 whitespace-nowrap">公式</span>
              <span className="rounded-md bg-[#f9ecfb] px-2 py-0.5 text-[#c13ddb]">
                {summary.formula}
              </span>
            </div>
          ) : null}
        </div>
      ) : null}
    </>
  );
};

const DataChat: CuteType.FC<{
  data?: DataChatSourceConfig;
}> = memo((props) => {
  const { data } = props;
  // 非对象数据降级为空配置，避免流式中间态把图表组件打崩。
  const chartCfg = useMemo(
    () => (typeof data === "object" && data ? data : {}),
    [data]
  );
  const [typeOverride, setTypeOverride] = useState<string | null>(null);
  const currentType = resolveCurrentChartType(chartCfg, typeOverride);

  const transConfig = useMemo(() => {
    // chartSuggest 只覆盖用户当前选择，其他轴、数据和预设由 buildChartConfig 统一合并。
    return buildChartConfig({
      ...chartCfg,
      chartSuggest: currentType,
    });
  }, [chartCfg, currentType]);

  return (
    <div className="mt-6 flex w-full max-w-[1200px] flex-col items-center rounded-2xl border border-[var(--chat-border)]/50 bg-white p-4 shadow-[var(--shadow-xs)] sm:p-5">
      <TypeBar
        currentType={currentType}
        chartCfg={chartCfg}
        onChange={(t) => setTypeOverride(t)}
      />
      <div className="w-full rounded-xl border border-[var(--chat-border)]/50 bg-[var(--chat-surface)] p-3 sm:p-4">
        {transConfig.chartType === "kpiGroup" && <Card data={transConfig} />}
        {transConfig.chartType === "table" && <SimpleTable data={transConfig} />}
        {defaultChartPresets.chartTypes.includes(transConfig.chartType) && (
          <Chart data={transConfig} />
        )}
      </div>
    </div>
  );
});

DataChat.displayName = "DataChat";

export default DataChat;
