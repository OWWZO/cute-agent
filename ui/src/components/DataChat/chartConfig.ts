import type { EChartsOption } from "echarts";

import { defaultChartPresets } from "./chartPresets";

// 数据对话先在这里把后端列/行契约转换成表格、KPI 或 ECharts option，组件只负责渲染。

export type DataChatChartType =
  | "line"
  | "bar"
  | "hbar"
  | "pie"
  | "table"
  | "kpiGroup";

export interface DataChatColumn {
  name: string;
  guid?: string;
  borderColor?: string;
  col?: string;
  dataType?: string | null;
  order?: number | null;
}

export interface DataChatFilter {
  operator?: string;
  name?: string;
  optName?: string;
  val?: string;
  subFilters?: DataChatFilter[];
}

export type DataChatRow = Record<string, unknown> & {
  key?: number;
};

export interface DataChatSourceConfig {
  chartSuggest?: string;
  dimCols?: string[];
  measureCols?: string[];
  columnList?: DataChatColumn[];
  dataList?: DataChatRow[];
  filters?: DataChatFilter[];
  overwriteCalc?: string;
  overwriteSource?: Record<string, string>;
}

export interface DataChatTableColumn {
  title: string;
  dataIndex: string;
  key: string;
}

export interface DataChatKpiItem {
  label: string;
  value: unknown;
  showValue: unknown;
}

export interface DataChatTransformedConfig {
  chartType: DataChatChartType | string;
  option?: EChartsOption;
  columnList?: DataChatTableColumn[];
  dataList?: DataChatRow[];
  kpiList?: DataChatKpiItem[];
}

type ChartDatum = {
  value: unknown;
  showValue: unknown;
};

function clonePlainObject<T>(value: T): T {
  return JSON.parse(JSON.stringify(value ?? {})) as T;
}

/**
 * 只复制会被 formatData 写入的行，避免把整份图表配置做 JSON 往返。
 * 行内值均为标量或嵌套对象引用，浅拷贝即可保证不修改上游数据。
 */
function cloneSourceConfig(cfg: DataChatSourceConfig): DataChatSourceConfig {
  if (!cfg.dataList) {
    return cfg;
  }
  return {
    ...cfg,
    dataList: cfg.dataList.map((row) => ({ ...row })),
  };
}

function findColumn(columns: DataChatColumn[], guid: string) {
  return columns.find((item) => item.guid === guid);
}

/** 后端列元数据缺失时退回 guid，避免整块图表因取不到列名而崩溃。 */
function findColumnName(columns: DataChatColumn[], guid: string | undefined) {
  if (!guid) {
    return "";
  }
  return findColumn(columns, guid)?.name || guid;
}

function resolveTableColumnKey(column: DataChatColumn) {
  return column.guid || column.col || "";
}

function resolveKpiColumnKey(column: DataChatColumn) {
  return column.guid || column.borderColor || "";
}

function formatSeriesData(dataList: DataChatRow[], key: string): ChartDatum[] {
  return dataList.map((item) => ({
    value: item[key],
    showValue: item[`${key}_format`],
  }));
}

function createTooltipElement(styles: Record<string, string> = {}) {
  const element = document.createElement("div");
  Object.assign(element.style, styles);
  return element;
}

function createTooltipRow(color: string, seriesName: unknown, showValue: unknown) {
  const row = createTooltipElement({
    display: "flex",
    fontSize: "12px",
    marginTop: "3px",
    alignItems: "center",
    gap: "10px",
  });
  const dot = createTooltipElement({
    width: "10px",
    height: "10px",
    borderRadius: "50%",
    backgroundColor: color,
  });
  const name = createTooltipElement({ color: "#6a6a6a" });
  name.textContent = String(seriesName);
  const value = createTooltipElement({
    color: "#181818",
    flex: "1",
    textAlign: "end",
  });
  value.textContent = String(showValue);
  row.appendChild(dot);
  row.appendChild(name);
  row.appendChild(value);
  return { row, value };
}

function buildAxisTooltip(
  params: Array<{
    seriesName: string;
    name: string;
    color: string;
    data: ChartDatum;
  }>
) {
  const container = createTooltipElement();
  params.forEach((series, index) => {
    if (index === 0) {
      const title = createTooltipElement();
      title.textContent = String(series.name);
      container.appendChild(title);
    }
    container.appendChild(
      createTooltipRow(series.color, series.seriesName, series.data.showValue).row
    );
  });
  return container;
}

function attachHorizontalZoom(option: Record<string, unknown>, labels: unknown[]) {
  if (labels.length > 10) {
    option.dataZoom = [
      {
        type: "slider",
        show: true,
        brushSelect: false,
        height: 25,
        showDetail: false,
        startValue: labels.length - 11,
        endValue: labels.length - 1,
      },
    ];
  }
}

function formatChartNumber(
  value: number,
  format: typeof defaultChartPresets.dataFormat
) {
  const factor = 10 ** format.digits;
  const rounded = Math.round(value * factor) / factor;
  const text = rounded.toFixed(format.digits);
  if (!format.thousandflag) {
    return text;
  }
  // 千分位只作用于整数部分，小数位不插入分隔符。
  const [integerPart, decimalPart] = text.split(".");
  const grouped = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return decimalPart ? `${grouped}.${decimalPart}` : grouped;
}

function formatData(cfg: DataChatSourceConfig) {
  const { dataFormat } = defaultChartPresets;
  const dataList = cfg.dataList || [];
  // 格式化值和原始值并存，图表计算使用原始字段，展示层使用 *_format 字段。
  dataList.forEach((row) => {
    Object.keys(row).forEach((key) => {
      if (key.endsWith("_format")) {
        return;
      }

      let value = typeof row[key] === "undefined" || row[key] === null ? "-" : row[key];
      if (!isNaN(Number(value)) && value !== "-") {
        value = formatChartNumber(Number(value), dataFormat);

        const [integerPart, decimalPart] = `${value}`.split(".");
        if (decimalPart) {
          const unit = `${decimalPart}`.split("").reduce((result, char) => {
            if (isNaN(Number(char))) {
              result += char;
            }
            return result;
          }, "");
          if (dataFormat.fillZero === false) {
            const [, trimmed = ""] = Number.parseFloat(`0.${decimalPart}`)
              .toString()
              .split(".");
            value = `${trimmed ? `${integerPart}.${trimmed}` : integerPart}${unit}`;
          }
        }

        row[`${key}_format`] = value;
      }
    });
  });
}

function initTable(cfg: DataChatSourceConfig) {
  const columnList = cfg.columnList || [];
  const dataList = cfg.dataList || [];
  return {
    columnList: columnList.map((column) => ({
      title: column.name,
      dataIndex: resolveTableColumnKey(column),
      key: resolveTableColumnKey(column),
    })),
    dataList: dataList.map((item, index) => ({
      ...item,
      key: index,
    })),
  };
}

function initChartOption(
  cfg: DataChatSourceConfig,
  chartType: DataChatChartType
): EChartsOption | Record<string, unknown> {
  // 各图表分支共享主题模板，但分别构造坐标轴、series 和 tooltip 所需的数据形状。
  const dimCols = cfg.dimCols || [];
  const measureCols = cfg.measureCols || [];
  const dataList = cfg.dataList || [];
  const columnList = cfg.columnList || [];
  const common = clonePlainObject(defaultChartPresets.templateCommon);

  if (chartType === "line") {
    const typeOption = clonePlainObject(defaultChartPresets.templateline);
    const option = Object.assign({}, common, typeOption) as Record<string, unknown>;
    option.xAxis = {
      ...(option.xAxis as Record<string, unknown>),
      data: dataList.map((item) => item[dimCols[0]]),
    };
    option.series = measureCols.map((measureKey) => ({
      type: "line",
      name: findColumnName(columnList, measureKey),
      data: formatSeriesData(dataList, measureKey),
      smooth: true,
      symbol: "circle",
      symbolSize: 10,
      showSymbol: false,
      itemStyle: {
        borderWidth: 2,
        borderColor: "#fff",
      },
      columnId: measureKey,
      label: {},
    }));
    attachHorizontalZoom(option, (option.xAxis as { data?: unknown[] }).data || []);
    option.tooltip = {
      ...(option.tooltip as Record<string, unknown>),
      formatter: buildAxisTooltip,
    };
    return option;
  }

  if (chartType === "bar") {
    const typeOption = clonePlainObject(defaultChartPresets.templatebar);
    const option = Object.assign({}, common, typeOption) as Record<string, unknown>;
    option.xAxis = {
      ...(option.xAxis as Record<string, unknown>),
      data: dataList.map((item) => item[dimCols[0]]),
    };
    option.series = measureCols.map((measureKey) => ({
      type: "bar",
      name: findColumnName(columnList, measureKey),
      label: {
        fontWeight: "bold",
        color: "#1b1b1b",
        fontSize: "12px",
      },
      data: formatSeriesData(dataList, measureKey),
      barMaxWidth: 32,
      itemStyle: { borderRadius: 4 },
      emphasis: { label: { show: false } },
    }));
    attachHorizontalZoom(option, (option.xAxis as { data?: unknown[] }).data || []);
    option.tooltip = {
      ...(option.tooltip as Record<string, unknown>),
      formatter: buildAxisTooltip,
    };
    return option;
  }

  if (chartType === "hbar") {
    const typeOption = clonePlainObject(defaultChartPresets.templatehbar);
    const option = Object.assign({}, common, typeOption) as Record<string, unknown>;
    option.yAxis = {
      ...(option.yAxis as Record<string, unknown>),
      data: dataList.map((item) => item[dimCols[0]]),
    };
    option.series = measureCols.map((measureKey) => ({
      name: findColumnName(columnList, measureKey),
      data: formatSeriesData(dataList, measureKey),
      label: {
        fontWeight: "bold",
        color: "#1b1b1b",
        fontSize: "12px",
      },
      type: "bar",
      barMaxWidth: 32,
      itemStyle: { borderRadius: 4 },
      labelLayout: { hideOverlap: true },
      emphasis: { label: { show: false } },
    }));

    const yAxisData = (option.yAxis as { data?: unknown[] }).data || [];
    if (yAxisData.length > 10) {
      option.dataZoom = [
        {
          type: "slider",
          show: true,
          brushSelect: false,
          showDetail: false,
          startValue: yAxisData.length - 11,
          endValue: yAxisData.length - 1,
          width: 25,
          yAxisIndex: 0,
          left: "auto",
          right: 5,
        },
      ];
    }
    option.tooltip = {
      ...(option.tooltip as Record<string, unknown>),
      formatter: buildAxisTooltip,
    };
    return option;
  }

  if (chartType === "pie") {
    const typeOption = clonePlainObject(defaultChartPresets.templatepie);
    const option = Object.assign({}, common, typeOption) as Record<string, unknown>;
    const dimColumnName = findColumnName(columnList, dimCols[0]);
    option.series = [
      {
        name: dimColumnName,
        type: "pie",
        radius: ["0%", "55%"],
        label: {
          position: "outer",
          alignTo: "edge",
          bleedMargin: 5,
          distanceToLabelLine: 10,
        },
        data: dataList.map((item) => ({
          name: item[dimCols[0]],
          value: item[measureCols[0]],
          showValue: item[`${measureCols[0]}_format`],
        })),
      },
    ];
    option.tooltip = {
      ...(option.tooltip as Record<string, unknown>),
      formatter: (series: {
        seriesName: string;
        name: string;
        color: string;
        data: ChartDatum;
        percent: number;
      }) => {
        const container = createTooltipElement();
        const title = createTooltipElement();
        title.textContent = String(series.name);
        container.appendChild(title);
        const { row, value } = createTooltipRow(
          series.color,
          series.seriesName,
          series.data.showValue
        );
        value.textContent = `${String(series.data.showValue)} (${series.percent}%)`;
        container.appendChild(row);
        return container;
      },
    };
    return option;
  }

  return {};
}

export function resolveCurrentChartType(
  cfg: DataChatSourceConfig,
  typeOverride: string | null
): string {
  return typeOverride ?? resolveChartType(cfg);
}

export function resolveChartType(cfg: DataChatSourceConfig): DataChatChartType {
  // 决策优先保证可读性：无法满足稳定维度/度量关系时退回表格，而不是猜测图表。
  let chartType: DataChatChartType = "table";
  const dimCols = cfg.dimCols || [];
  const measureCols = cfg.measureCols || [];
  const dataList = cfg.dataList || [];
  const columnList = cfg.columnList || [];

  if (dimCols.length === 1 && measureCols.length > 0 && dataList.length > 1) {
    if (findColumn(columnList, dimCols[0])?.dataType === "DATE") {
      chartType = "line";
    } else if (findColumn(columnList, measureCols[0])?.order != null) {
      chartType = "hbar";
    } else if (dataList.length < 6) {
      chartType = "pie";
    } else if (dataList.length >= 6) {
      chartType = "bar";
    }
  } else if (cfg.overwriteCalc) {
    chartType = "kpiGroup";
  } else if (
    dataList.length === 1 &&
    columnList.length > 0 &&
    columnList.findIndex((column) => isNaN(Number(dataList[0][column.guid || ""]))) === -1 &&
    columnList.length <= 8
  ) {
    chartType = "kpiGroup";
  }

  return chartType;
}

export function buildChartConfig(chartCfg: DataChatSourceConfig): DataChatTransformedConfig {
  // 克隆输入避免格式化函数修改上游 SSE/缓存对象，随后只生成当前视图需要的字段。
  const nextConfig = cloneSourceConfig(chartCfg);
  formatData(nextConfig);
  const chartType = nextConfig.chartSuggest || "table";
  const result: DataChatTransformedConfig = { chartType };

  if (defaultChartPresets.chartTypes.includes(chartType)) {
    result.option = initChartOption(nextConfig, chartType as DataChatChartType);
  }

  if (chartType === "table") {
    const { columnList, dataList } = initTable(nextConfig);
    result.columnList = columnList;
    result.dataList = dataList;
  }

  if (chartType === "kpiGroup") {
    const columnList = nextConfig.columnList || [];
    const dataList = nextConfig.dataList || [];
    const firstRow = dataList[0] || {};
    result.kpiList = columnList.map((column) => {
      const key = resolveKpiColumnKey(column);
      return {
        label: column.name,
        value: firstRow[key],
        showValue: firstRow[`${key}_format`],
      };
    });
  }

  return result;
}
