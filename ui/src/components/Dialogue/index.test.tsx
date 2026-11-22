import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import Dialogue from "./index";

function wakeChat(loading: boolean): CHAT.ChatItem {
  return {
    sessionId: "session-1",
    requestId: "wake-1",
    query: "后台任务唤醒消息",
    files: [],
    forceStop: false,
    multiAgent: { tasks: [] },
    loading,
    tasks: [],
    backgroundWake: true,
  } as unknown as CHAT.ChatItem;
}

describe("Dialogue background wake", () => {
  it("隐藏唤醒消息的用户气泡和撤销入口，并明确显示继续处理状态", () => {
    const running = renderToStaticMarkup(
      <Dialogue chat={wakeChat(true)} isClusterMode={false} onUndo={() => undefined} />
    );

    expect(running).not.toContain("data-user-message-anchor");
    expect(running).toContain("后台任务已完成，正在继续处理");
  });

  it("唤醒轮完成后只显示来源徽标", () => {
    const finished = renderToStaticMarkup(
      <Dialogue chat={wakeChat(false)} isClusterMode={false} onUndo={() => undefined} />
    );

    expect(finished).not.toContain("data-user-message-anchor");
    expect(finished).toContain("后台任务已完成");
  });
});
