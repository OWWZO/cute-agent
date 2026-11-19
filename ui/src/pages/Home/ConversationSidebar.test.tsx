import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import ConversationSidebar from "./ConversationSidebar";

const baseSession = {
  sessionId: "session-tree-1",
  title: "会话树测试",
  status: "SUCCESS",
  latestQueryText: "测试问题",
  runCount: 1,
  finishedRunCount: 1,
  failedRunCount: 0,
  startedAt: "2026-01-01T00:00:00",
  lastActiveAt: "2026-01-01T00:00:00",
};

function renderSidebarWithSession(
  overrides: Partial<typeof baseSession & { subSessionCount: number | null }>
) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <ConversationSidebar
        activeView="chat"
        recentSessions={[{ ...baseSession, ...overrides }]}
        recentSessionsLoading={false}
        recentSessionsLoadingMore={false}
        recentSessionsHasMore={false}
        user={null}
        onNewChat={() => {}}
        onSelectSession={() => {}}
        onLoadMoreRecentSessions={() => {}}
        onChangeView={() => {}}
        onManageFeaturedConversation={() => {}}
        onLogout={() => {}}
      />
    </MemoryRouter>
  );
}

describe("ConversationSidebar", () => {
  it("renders the featured conversations navigation entry", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ConversationSidebar
          activeView="chat"
          recentSessions={[]}
          recentSessionsLoading={false}
          recentSessionsLoadingMore={false}
          recentSessionsHasMore={false}
          user={null}
          onNewChat={() => {}}
          onSelectSession={() => {}}
          onLoadMoreRecentSessions={() => {}}
          onChangeView={() => {}}
          onManageFeaturedConversation={() => {}}
          onLogout={() => {}}
        />
      </MemoryRouter>
    );

    expect(html).toContain("精品对话");
    expect(html).toContain("子 Agent");
    expect(html).toContain("工具");
    expect(html).toContain("查看当前会话的文件");
  });

  it("renders task file panel with back action", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ConversationSidebar
          activeView="chat"
          recentSessions={[]}
          recentSessionsLoading={false}
          recentSessionsLoadingMore={false}
          recentSessionsHasMore={false}
          user={null}
          sidebarPanel="task-files"
          taskList={[]}
          onNewChat={() => {}}
          onSelectSession={() => {}}
          onLoadMoreRecentSessions={() => {}}
          onChangeView={() => {}}
          onManageFeaturedConversation={() => {}}
          onLogout={() => {}}
          onCloseTaskFiles={() => {}}
        />
      </MemoryRouter>
    );

    expect(html).toContain("文件");
    expect(html).toContain("返回");
    expect(html).toContain("当前会话暂无文件");
  });

  it("没有子会话时不展示展开入口", () => {
    const html = renderSidebarWithSession({ subSessionCount: 0 });

    expect(html).not.toContain("展开子会话");
  });

  it("有子会话时保留展开入口", () => {
    const html = renderSidebarWithSession({ subSessionCount: 1 });

    expect(html).toContain("展开子会话");
  });

  it("子会话数量未知时默认不展示展开入口", () => {
    const html = renderSidebarWithSession({ subSessionCount: null });

    expect(html).not.toContain("展开子会话");
  });
});
