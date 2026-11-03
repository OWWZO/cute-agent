import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import FeaturedConversationCard from "./FeaturedConversationCard";

function renderCard(props: {
  title: string;
  summary: string;
  variant?: "grid" | "row";
}) {
  return renderToStaticMarkup(
    <FeaturedConversationCard
      card={{
        featuredId: "featured-1",
        sessionId: "session-1",
        title: props.title,
        summary: props.summary,
        tags: [],
      }}
      variant={props.variant}
      onSelect={vi.fn()}
    />
  );
}

describe("FeaturedConversationCard", () => {
  it.each(["grid", "row"] as const)(
    "%s 布局下摘要与标题重复时只渲染一次",
    (variant) => {
      const text = "围绕 Notion、Obsidian 等代表产品做一次竞品调研";
      const html = renderCard({ title: text, summary: `${text}。`, variant });

      expect(html.match(/竞品调研/g)).toHaveLength(1);
    }
  );

  it("摘要包含标题之外的信息时照常渲染", () => {
    const html = renderCard({
      title: "分析糖尿病患病率趋势",
      summary: "分析糖尿病患病率趋势，并绘制趋势图表。",
    });

    expect(html).toContain("并绘制趋势图表");
  });
});
