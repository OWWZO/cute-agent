import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import AgentProfilePicker from "./AgentProfilePicker";

const agents = [
  {
    agentKey: "general-purpose",
    displayName: "通用助手",
    whenToUse: "通用任务",
  },
  {
    agentKey: "data-analysis",
    displayName: "数据分析",
    whenToUse: "表格与指标分析",
  },
];

describe("AgentProfilePicker", () => {
  it("普通模式展示当前选中的 Agent", () => {
    const html = renderToStaticMarkup(
      <AgentProfilePicker
        agents={agents}
        value="data-analysis"
        collaborationMode="normal"
        onChange={() => {}}
      />
    );

    expect(html).toContain("数据分析");
    expect(html).not.toContain("协调者");
    expect(html).toContain("当前智能体：数据分析");
  });

  it("集群模式固定显示协调者并禁用普通 Agent 选择", () => {
    const html = renderToStaticMarkup(
      <AgentProfilePicker
        agents={agents}
        value={null}
        collaborationMode="cluster"
        onChange={() => {}}
      />
    );

    expect(html).toContain("协调者");
    expect(html).toMatch(/disabled=""/);
  });
});
