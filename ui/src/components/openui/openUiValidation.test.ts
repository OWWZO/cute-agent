import { describe, expect, it } from 'vitest';

import { normalizeOpenUiProgram } from '@/lib/openui/normalize';
import { diagnoseProgram, isRenderableProgram } from './openUiValidation';

/** 典型模型输出：核心组件 + Chart，应当通过校验（否则每张界面都会被降级）。 */
const GOOD_PROGRAM = `root = Col([head, kpis, chart, note], 5)
head = Title("Q3 营收报告", "xl")
kpis = Grid([k1, k2, k3], 3, 180, 4)
k1 = Card([Title("¥12.4M", "2xl"), Caption("总营收")], "soft", 4, 3)
k2 = Card([Title("+8.2%", "2xl", "success"), Caption("同比增长")], "soft", 4, 3)
k3 = Card([Title("3.1%", "2xl", "warning"), Caption("退货率")], "soft", 4, 3)
chart = Chart("bar", [{month: "7月", value: 12}, {month: "8月", value: 15}, {month: "9月", value: 18}], "month", ["value"], "currency", "CNY", null, 200)
note = Text("数据来源：ERP 系统，未经审计。", "xs", "muted")`;

/** 带状态与交互的程序：@Each / @Set / 表单绑定。 */
const INTERACTIVE_PROGRAM = `root = Col([title, slider, out])
$budget = 40
title = Title("预算试算", "lg")
slider = Slider($budget, 0, 100, 5)
out = Card([Text("每月可支配：" + @Format($budget * 0.3, "currency", 2, "CNY"))], "soft")`;

describe('isRenderableProgram', () => {
  it('accepts a typical dashboard program', () => {
    expect(isRenderableProgram(GOOD_PROGRAM)).toBe(true);
  });

  it('accepts a program with state bindings', () => {
    expect(isRenderableProgram(INTERACTIVE_PROGRAM)).toBe(true);
  });

  it('accepts a chart whose data is produced by an OpenUI function', () => {
    const dynamicChart = `root = Chart("donut", @GroupBy(orders, "region", "amount"), "key", ["value"], "currency")
orders = [{"region": "A", "amount": 10}, {"region": "B", "amount": 20}]`;

    expect(isRenderableProgram(dynamicChart)).toBe(true);
  });

  it('rejects an empty program', () => {
    expect(isRenderableProgram('   ')).toBe(false);
  });

  it('rejects a program without root', () => {
    expect(isRenderableProgram('head = Title("Q3", "xl")')).toBe(false);
  });

  it('rejects an unknown component', () => {
    expect(isRenderableProgram('root = Col([a])\na = FancyThing("x")')).toBe(false);
  });

  it('rejects an unresolved reference', () => {
    expect(isRenderableProgram('root = Col([missing])')).toBe(false);
  });

  it('rejects a truncated program', () => {
    expect(isRenderableProgram('root = Col([head]\nhead = Title("Q3"')).toBe(false);
  });
});

/**
 * 与真实 parser 联调：本地纠偏的价值全在"纠完之后真的能渲染"，
 * 只断言字符串替换的结果是不够的。
 */
describe('isRenderableProgram after local normalization', () => {
  it('rescues a program whose actions were written in the slash form', () => {
    // 模型把 @Set 写成 @//set/( 时 parser 在该行中断：语句变孤儿、下游引用悬空、root 判 incomplete。
    const broken = '$display = 0\nb = Button("7", @//set/($display, $display * 10 + 7))\nroot = Col([b])';
    expect(isRenderableProgram(broken)).toBe(false);

    const normalized = normalizeOpenUiProgram(broken);
    expect(normalized).not.toBeNull();
    expect(isRenderableProgram(normalized as string)).toBe(true);
  });

  it('rescues a program with a multi-step Action(...)', () => {
    const broken = `$display = 0
$stored = 0
$op = ""
b = Button("÷", Action([@//set/($stored, $display), @//set/($op, "÷"), @//set/($display, 0)]))
c = Button("C", @//reset/($display, $stored, $op))
root = Col([b, c])`;
    expect(isRenderableProgram(broken)).toBe(false);
    expect(isRenderableProgram(normalizeOpenUiProgram(broken) as string)).toBe(true);
  });

  it('leaves a program that already renders alone', () => {
    const valid = '$display = 0\nb = Button("7", @Set($display, 7))\nroot = Col([b])';
    expect(isRenderableProgram(valid)).toBe(true);
    // 已经能渲染的程序不该被纠偏改动 —— 调用方据此保证不会把好程序改坏。
    expect(normalizeOpenUiProgram(valid)).toBeNull();
  });

  it('clamps an oversized Text size so the program renders', () => {
    // Text 的 size 只到 lg，xl 及以上是 Title 才有的 —— 模型想要大号数字却选错了组件。
    const oversized = '$display = 0\nt = Text($display + "", "3xl", null, "semibold", false, false, true)\nroot = Col([t])';
    expect(isRenderableProgram(oversized)).toBe(false);
    expect(isRenderableProgram(normalizeOpenUiProgram(oversized) as string)).toBe(true);
  });

  it('rescues a program broken in both ways at once', () => {
    // 真实案例：action 写成路径形态 + Text 用了 Title 才支持的字号。
    const broken = `$display = 0
t = Text($display + "", "3xl")
b = Button("C", @//reset/($display))
root = Col([t, b])`;
    expect(isRenderableProgram(broken)).toBe(false);
    expect(isRenderableProgram(normalizeOpenUiProgram(broken) as string)).toBe(true);
  });

  it('rescues chart data whose bare Chinese keys were discarded by the parser', () => {
    const broken = `root = Chart("line", [{年: 2016, 出生率: 12.95, 结婚率: 8.3}, {年: 2017, 出生率: 12.43, 结婚率: 7.7}], "年", ["出生率", "结婚率"])`;

    const diagnosis = diagnoseProgram(broken);
    expect(diagnosis.renderable).toBe(false);
    expect(diagnosis.errors.some((error) => error.code === 'chart-x-missing')).toBe(true);

    const normalized = normalizeOpenUiProgram(broken);
    expect(normalized).not.toBeNull();
    expect(isRenderableProgram(normalized as string)).toBe(true);
  });

  it('detects bare Chinese keys inside dynamic @Each chart data', () => {
    const broken = `phaseData = [{id:"P1",budget:180},{id:"P2",budget:520}]
chartData = @Each(phaseData, "p", {阶段:p.id, 预算:p.budget})
root = Chart("bar", chartData, "阶段", ["预算"], "number")`;

    const diagnosis = diagnoseProgram(broken);
    expect(diagnosis.renderable).toBe(false);
    expect(
      diagnosis.errors.some((error) => error.code === 'chart-keys-lost')
    ).toBe(true);

    const normalized = normalizeOpenUiProgram(broken);
    expect(normalized).not.toBeNull();
    expect(isRenderableProgram(normalized as string)).toBe(true);
  });

  it('rescues chart keys with units, spaces, or a leading digit', () => {
    const broken = `root = Chart("line", [{2025年: 1, 营收（元）: 14800, 出生 人口: 954}], "2025年", ["营收（元）", "出生 人口"])`;

    expect(isRenderableProgram(broken)).toBe(false);
    const normalized = normalizeOpenUiProgram(broken);
    expect(normalized).not.toBeNull();
    expect(isRenderableProgram(normalized as string)).toBe(true);
  });

  it('rescues member access on function results with Chinese field names', () => {
    const broken = `d = [{"年份": 2016, "出生率": 12.95, "出生人口": 1883}]
first = @First(d)
last = @Last(d)
sel = @Find(d, "年份", 2016)
root = Text(last.出生率 + "‰ " + sel.出生人口 + " " + first.年份, "lg", "ink", "semibold")`;

    expect(isRenderableProgram(broken)).toBe(false);
    const normalized = normalizeOpenUiProgram(broken);
    expect(normalized).not.toBeNull();
    expect(isRenderableProgram(normalized as string)).toBe(true);
  });

  it('rejects a chart whose selected series has no numeric values', () => {
    const bad = `root = Chart("line", [{"年": 2016, "出生率": "-"}, {"年": 2017, "出生率": "待发布"}], "年", ["出生率"])`;
    const diagnosis = diagnoseProgram(bad);

    expect(diagnosis.renderable).toBe(false);
    expect(diagnosis.errors.some((error) => error.code === 'chart-series-no-number')).toBe(true);
  });

  it('keeps formatted values that the Chart renderer already supports', () => {
    const formatted = `root = Chart("bar", [{"月份": "1月", "营收": "1,234.5"}, {"月份": "2月", "营收": "45%"}], "月份", ["营收"])`;

    expect(isRenderableProgram(formatted)).toBe(true);
  });
});

/**
 * diagnoseProgram 是意图修复链路（plans/038）的信息源：
 * 它必须保留 parser 给出的错误细节，同时与 isRenderableProgram 的判定严格一致。
 */
describe('diagnoseProgram', () => {
  it('agrees with isRenderableProgram on a good program', () => {
    const diagnosis = diagnoseProgram(GOOD_PROGRAM);
    expect(diagnosis.renderable).toBe(true);
    expect(diagnosis.incomplete).toBe(false);
    expect(diagnosis.hasRoot).toBe(true);
    expect(diagnosis.errors).toHaveLength(0);
  });

  it('reports component errors with statementId and path', () => {
    // Text 的 size 只接受 xs/sm/md/lg，path 指到 /size。
    const bad = '$display = 0\nt = Text($display + "", "3xl")\nroot = Col([t])';
    const diagnosis = diagnoseProgram(bad);
    expect(diagnosis.renderable).toBe(false);
    expect(diagnosis.errors.length).toBeGreaterThan(0);
    const withStatement = diagnosis.errors.find((error) => error.statementId);
    expect(withStatement).toBeDefined();
    expect(withStatement?.code).toBeTruthy();
  });

  it('marks truncated programs as incomplete with no usable statementId', () => {
    const diagnosis = diagnoseProgram('root = Col([head]\nhead = Title("Q3"');
    expect(diagnosis.renderable).toBe(false);
    expect(diagnosis.incomplete).toBe(true);
  });

  it('reports unresolved references', () => {
    const diagnosis = diagnoseProgram('root = Col([missing])');
    expect(diagnosis.renderable).toBe(false);
    expect(diagnosis.unresolved).toContain('missing');
  });

  it('treats blank input as incomplete', () => {
    const diagnosis = diagnoseProgram('   ');
    expect(diagnosis.renderable).toBe(false);
    expect(diagnosis.incomplete).toBe(true);
    expect(diagnosis.hasRoot).toBe(false);
  });
});
