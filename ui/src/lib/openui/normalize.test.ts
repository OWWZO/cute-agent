import { describe, expect, it } from "vitest";

import {
  normalizeOpenUiProgram,
  normalizeOpenUiUnicodeSyntax,
} from "./normalize";

describe("normalizeOpenUiProgram", () => {
  it("rewrites the slash form of @Set", () => {
    expect(normalizeOpenUiProgram(`b = Button("7", @//set/($display, 7))`)).toBe(
      `b = Button("7", @Set($display, 7))`
    );
  });

  it("rewrites the slash form of @Reset and keeps every argument", () => {
    expect(normalizeOpenUiProgram(`c = Button("C", @//reset/($display, $stored, $op))`)).toBe(
      `c = Button("C", @Reset($display, $stored, $op))`
    );
  });

  it("rewrites every step inside Action([...])", () => {
    const source = `d = Button("÷", Action([@//set/($stored, $display), @//set/($op, "÷")]))`;
    expect(normalizeOpenUiProgram(source)).toBe(
      `d = Button("÷", Action([@Set($stored, $display), @Set($op, "÷")]))`
    );
  });

  it("tolerates casing and stray whitespace", () => {
    expect(normalizeOpenUiProgram(`b = Button("7", @// SET / ($display, 7))`)).toBe(
      `b = Button("7", @Set($display, 7))`
    );
  });

  it("rewrites the other action steps too", () => {
    expect(normalizeOpenUiProgram(`b = Button("Go", @//openurl/("https://a.test"))`)).toBe(
      `b = Button("Go", @OpenUrl("https://a.test"))`
    );
    expect(normalizeOpenUiProgram(`b = Button("Ask", @//toassistant/("more"))`)).toBe(
      `b = Button("Ask", @ToAssistant("more"))`
    );
  });

  it("returns null when nothing looks malformed", () => {
    expect(normalizeOpenUiProgram(`b = Button("7", @Set($display, 7))`)).toBeNull();
    expect(normalizeOpenUiProgram("root = Col([t])\nt = Title(\"hi\")")).toBeNull();
  });

  it("leaves an unknown step name untouched", () => {
    // 宁可漏纠，也不要把没见过的写法改成别的意思。
    expect(normalizeOpenUiProgram(`b = Button("x", @//whatever/($a, 1))`)).toBeNull();
  });

  it("leaves a bare @// with no call parens untouched", () => {
    expect(normalizeOpenUiProgram(`t = Text("@//set/ 用法")`)).toBeNull();
  });
});

describe("normalizeOpenUiProgram — oversized Text size", () => {
  it("clamps a size only Title supports down to lg", () => {
    // 不换组件：换 Title 会丢掉 weight / tabularNums 这些参数，计算器那种等宽数字更要紧。
    expect(normalizeOpenUiProgram(`t = Text($display + "", "3xl", null, "semibold", false, false, true)`)).toBe(
      `t = Text($display + "", "lg", null, "semibold", false, false, true)`
    );
  });

  it("leaves Title alone, which does support 3xl", () => {
    expect(normalizeOpenUiProgram(`t = Title("¥12.4M", "3xl")`)).toBeNull();
  });

  it("leaves a legal Text size alone", () => {
    expect(normalizeOpenUiProgram(`t = Text("hi", "md", "muted")`)).toBeNull();
  });

  it("does not mistake a comma inside the content string for an argument separator", () => {
    expect(normalizeOpenUiProgram(`t = Text("a, b", "2xl")`)).toBe(`t = Text("a, b", "lg")`);
  });

  it("sees past a nested call in the content argument", () => {
    expect(normalizeOpenUiProgram(`t = Text(@Join(items, ", "), "xl")`)).toBe(
      `t = Text(@Join(items, ", "), "lg")`
    );
  });
});

describe("normalizeOpenUiProgram — builtin functions in slash form", () => {
  it("rewrites @//clamp/ to @Clamp(", () => {
    expect(
      normalizeOpenUiProgram(`p = @//clamp/(($checkpoint - 1) * 20, 0, 100)`)
    ).toBe(`p = @Clamp(($checkpoint - 1) * 20, 0, 100)`);
  });

  it("rewrites @//count/ to @Count( even when glued to operators", () => {
    // 模型输出里 >、=、, 经常和 @// 紧贴，正则不能要求左侧有空白。
    expect(
      normalizeOpenUiProgram(`t = checkpoints[@//clamp/($c - 1, 0,@//count/(checkpoints) - 1)]\nf = $c >@//count/(checkpoints)\ng =@//clamp/($c, 1, 5)`)
    ).toBe(
      `t = checkpoints[@Clamp($c - 1, 0,@Count(checkpoints) - 1)]\nf = $c >@Count(checkpoints)\ng =@Clamp($c, 1, 5)`
    );
  });

  it("rewrites functions nested inside @Set arguments", () => {
    expect(
      normalizeOpenUiProgram(`b = Button("←",@//set/($lane,@//clamp/($lane - 1, 1, 3)))`)
    ).toBe(`b = Button("←",@Set($lane,@Clamp($lane - 1, 1, 3)))`);
  });

  it("covers the remaining builtin functions by name", () => {
    for (const [slash, canonical] of [
      ["@//append/(", "@Append("],
      ["@//avg/(", "@Avg("],
      ["@//default/(", "@Default("],
      ["@//each/(", "@Each("],
      ["@//filter/(", "@Filter("],
      ["@//format/(", "@Format("],
      ["@//formatdate/(", "@FormatDate("],
      ["@//includes/(", "@Includes("],
      ["@//join/(", "@Join("],
      ["@//percent/(", "@Percent("],
      ["@//plural/(", "@Plural("],
      ["@//range/(", "@Range("],
      ["@//remove/(", "@Remove("],
      ["@//round/(", "@Round("],
      ["@//sum/(", "@Sum("],
      ["@//today/(", "@Today("],
      ["@//toggle/(", "@Toggle("],
    ] as const) {
      expect(normalizeOpenUiProgram(`x = ${slash}v)`)).toBe(`x = ${canonical}v)`);
    }
  });

  it("still leaves unknown slash names untouched", () => {
    expect(normalizeOpenUiProgram(`x = @//fancything/(v)`)).toBeNull();
  });
});

describe("normalizeOpenUiProgram — bare object keys", () => {
  it("quotes bare Chinese keys inside an @Each template", () => {
    const source =
      'chartData = @Each(phaseData, "p", {阶段:p.id, 预算:p.budget})';

    expect(normalizeOpenUiUnicodeSyntax(source)).toBe(
      'chartData = @Each(phaseData, "p", {"阶段":p.id, "预算":p.budget})'
    );
  });

  it("quotes bare Chinese keys in chart data", () => {
    const source =
      'root = Chart("line", [{年: 2016, 出生率: 12.95, 结婚率: 8.3}, {年: 2017, 出生率: 12.43, 结婚率: 7.7}], "年", ["出生率", "结婚率"])';

    expect(normalizeOpenUiProgram(source)).toBe(
      'root = Chart("line", [{"年": 2016, "出生率": 12.95, "结婚率": 8.3}, {"年": 2017, "出生率": 12.43, "结婚率": 7.7}], "年", ["出生率", "结婚率"])'
    );
  });

  it("leaves quoted keys and colon-like text inside strings alone", () => {
    expect(
      normalizeOpenUiProgram(
        'root = Chart("line", [{"年": 2016, "出生率": 12.95}], "年", ["出生率"])'
      )
    ).toBeNull();
    expect(normalizeOpenUiProgram('root = Text("说明, 年: 2025")')).toBeNull();
  });

  it("rewrites Chinese member access to bracket access", () => {
    const source =
      'root = Text(last.出生率 + "‰ " + sel.出生人口 + " 万", "lg", "ink", "semibold")';

    expect(normalizeOpenUiProgram(source)).toBe(
      'root = Text(last["出生率"] + "‰ " + sel["出生人口"] + " 万", "lg", "ink", "semibold")'
    );
  });

  it("quotes Chinese keys that contain units, spaces, or a leading digit", () => {
    const source =
      'root = Chart("line", [{2025年: 1, 营收（元）: 14800, 出生 人口: 954}], "2025年", ["营收（元）", "出生 人口"])';

    expect(normalizeOpenUiProgram(source)).toBe(
      'root = Chart("line", [{"2025年": 1, "营收（元）": 14800, "出生 人口": 954}], "2025年", ["营收（元）", "出生 人口"])'
    );
  });

  it("does not mistake a ternary value after a comma for an object key", () => {
    const source =
      'root = Chart("line", [{value: enabled ? 1 : 2}, {value: 3}], "value", ["value"])';

    expect(normalizeOpenUiProgram(source)).toBeNull();
  });
});
