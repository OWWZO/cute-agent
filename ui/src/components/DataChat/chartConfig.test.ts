import { describe, expect, it } from "vitest";

import { buildChartConfig, resolveChartType, resolveCurrentChartType } from "./chartConfig";

describe("chartConfig", () => {
  it("构建图表配置时不应修改输入对象", () => {
    const input = {
      chartSuggest: "line",
      dimCols: ["dt"],
      measureCols: ["gmv"],
      columnList: [
        {
          guid: "dt",
          name: "日期",
          dataType: "DATE",
        },
        {
          guid: "gmv",
          name: "GMV",
          order: null,
        },
      ],
      dataList: [
        {
          dt: "2026-05-01",
          gmv: 10,
        },
      ],
    };
    const frozen = Object.freeze({ ...input });

    const result = buildChartConfig(frozen);

    expect(result.chartType).toBe("line");
    expect(frozen.chartSuggest).toBe("line");
    expect(frozen.dataList[0]).not.toHaveProperty("gmv_format");
  });

  // 回归：后端 columnList 与 dimCols/measureCols 不一致时，
  // findColumn(...)!.name 会抛 TypeError，整块图表白屏。
  it.each(["line", "bar", "hbar", "pie"])(
    "列元数据缺失时 %s 图表不应崩溃",
    (chartSuggest) => {
      const input = {
        chartSuggest,
        dimCols: ["dt"],
        measureCols: ["gmv"],
        columnList: [],
        dataList: [
          {
            dt: "2026-05-01",
            gmv: 10
          },
          {
            dt: "2026-05-02",
            gmv: 20
          },
        ],
      };

      expect(() => buildChartConfig(input)).not.toThrow();
    }
  );
});

describe("buildChartConfig 数值格式化", () => {
  it("按预设描述补千分位并按 digits 截断小数", () => {
    const result = buildChartConfig({
      chartSuggest: "table",
      columnList: [
        {
          guid: "num",
          name: "数值",
        },
        {
          guid: "dt",
          name: "日期",
        },
      ],
      dataList: [
        {
          num: 1234.5,
          dt: "a",
        },
        {
          num: -1000,
          dt: "b",
        },
        {
          num: 12.346,
          dt: "c",
        },
      ],
    });

    expect(result.dataList?.map((row) => row.num_format)).toEqual([
      "1,234.5",
      "-1,000",
      "12.35",
    ]);
  });
});

describe("resolveCurrentChartType", () => {
  const completeCfg = {
    dimCols: ["dt"],
    measureCols: ["gmv"],
    dataList: [
      {
        dt: "2026-05-01",
        gmv: 10,
      },
      {
        dt: "2026-05-02",
        gmv: 20,
      },
    ],
    columnList: [
      {
        guid: "dt",
        name: "日期",
        dataType: "STRING",
      },
      {
        guid: "gmv",
        name: "GMV",
      },
    ],
  };

  it("未手动选择时跟随当前数据自动重算", () => {
    expect(resolveCurrentChartType({}, null)).toBe("table");
    expect(resolveCurrentChartType(completeCfg, null)).toBe("pie");
  });

  it("用户手动选择后保持覆盖值", () => {
    expect(resolveCurrentChartType(completeCfg, "bar")).toBe("bar");
  });
});

describe("resolveChartType", () => {
  const build = (order?: number | null) => ({
    dimCols: ["dt"],
    measureCols: ["gmv"],
    dataList: [
      {
        dt: "2026-05-01",
        gmv: 10
      },
      {
        dt: "2026-05-02",
        gmv: 20
      },
    ],
    columnList: [
      {
        guid: "dt",
        name: "日期",
        dataType: "STRING"
      },
      {
        guid: "gmv",
        name: "GMV",
        order
      },
    ],
  });

  // 回归：order 是可选字段，undefined !== null 为 true，
  // 导致没有 order 的度量列被误判成 hbar。
  it("缺少 order 字段时不再误判为 hbar", () => {
    expect(resolveChartType(build(undefined))).not.toBe("hbar");
    expect(resolveChartType(build(null))).not.toBe("hbar");
  });

  it("显式 order 仍使用 hbar", () => {
    expect(resolveChartType(build(1))).toBe("hbar");
  });
});
