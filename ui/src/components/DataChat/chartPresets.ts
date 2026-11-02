// 三套坐标轴预设共用同一套轴线 / 刻度 / 标签样式，避免单个预设改色后视觉漂移。
const AXIS_LINE_STYLE = { lineStyle: { color: "#E1E3E6" } } as const;

const AXIS_TICK_STYLE = { lineStyle: { color: "#E1E3E6" } } as const;

const AXIS_LABEL_STYLE = { color: "#898E99" } as const;

const CATEGORY_AXIS_LABEL_STYLE = {
  ...AXIS_LABEL_STYLE,
  show: true,
  interval: "auto",
  rotate: 0,
} as const;

const SPLIT_LINE_STYLE = {
  lineStyle: {
    type: "dashed",
    color: "#E1E3E6",
  },
} as const;

// 这些预设只描述通用视觉约束；具体数据、坐标轴方向和 series 由 chartConfig 运行时补齐。
export const defaultChartPresets = {
  chartTypes: ["line", "bar", "hbar", "pie"],
  templateline: {
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      className: "custom_tooltip",
    },
    xAxis: {
      axisLine: AXIS_LINE_STYLE,
      axisTick: AXIS_TICK_STYLE,
      axisLabel: CATEGORY_AXIS_LABEL_STYLE,
      type: "category",
      triggerEvent: true,
    },
    yAxis: {
      splitLine: SPLIT_LINE_STYLE,
      axisLabel: AXIS_LABEL_STYLE,
      axisLine: AXIS_LINE_STYLE,
      axisTick: AXIS_TICK_STYLE,
      triggerEvent: true,
    },
  },
  templatebar: {
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      className: "custom_tooltip",
      axisPointer: {
        type: "shadow",
        shadowStyle: {
          color: "rgba(0,0,0,0.04)",
        },
      },
    },
    xAxis: {
      axisLine: AXIS_LINE_STYLE,
      axisTick: AXIS_TICK_STYLE,
      axisLabel: CATEGORY_AXIS_LABEL_STYLE,
      data: [],
      dataZoom: {},
      triggerEvent: true,
    },
    yAxis: {
      splitLine: SPLIT_LINE_STYLE,
      axisLabel: AXIS_LABEL_STYLE,
      axisLine: AXIS_LINE_STYLE,
      axisTick: AXIS_TICK_STYLE,
      triggerEvent: true,
    },
  },
  templatehbar: {
    tooltip: {
      axisPointer: {
        type: "shadow",
        shadowStyle: {
          color: "rgba(0,0,0,0.04)",
        },
      },
      trigger: "axis",
      appendToBody: true,
      className: "custom_tooltip",
    },
    xAxis: {
      axisLine: AXIS_LINE_STYLE,
      axisTick: AXIS_TICK_STYLE,
      axisLabel: CATEGORY_AXIS_LABEL_STYLE,
      triggerEvent: true,
    },
    yAxis: {
      splitLine: SPLIT_LINE_STYLE,
      axisLabel: AXIS_LABEL_STYLE,
      axisLine: AXIS_LINE_STYLE,
      axisTick: AXIS_TICK_STYLE,
      data: [],
      inverse: true,
      nameLocation: "start",
      triggerEvent: true,
    },
    grid: {
      top: "20",
      right: "60",
      bottom: "10",
      left: "4%",
      containLabel: true,
    },
  },
  templatepie: {
    tooltip: {
      trigger: "item",
      appendToBody: true,
      className: "custom_tooltip",
    },
  },
  templateCommon: {
    color: [
      "#4687F7",
      "#48D7F1",
      "#53B2EE",
      "#82D78F",
      "#FCC55A",
      "#B383F9",
      "#FA86B9",
      "#6E7FED",
      "#CD64E2",
      "#F57546",
      "#898E99",
    ],
    grid: {
      top: "20",
      right: "4%",
      bottom: "60",
      left: "4%",
      containLabel: true,
    },
  },
  /**
   * 数值格式化描述，由 chartConfig 的固定实现消费。
   *
   * 不再携带可执行 JS 字符串：`new Function` 既是注入面，`(?<=...)` 这类后行断言
   * 在旧浏览器上还会直接抛语法错误。这里只保留真正生效的字段，避免两半配置漂移。
   */
  dataFormat: {
    digits: 2,
    thousandflag: true,
    fillZero: false,
  },
};
