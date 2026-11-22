import { describe, expect, it } from "vitest";

import {
  asSessionTaskSnapshot,
  findLatestSessionTasks,
  resolveSessionTaskSnapshot,
} from "./sessionTaskModel";

describe("findLatestSessionTasks", () => {
  const task = (messageType: string, id: string) =>
    ({ messageType, messageId: id }) as unknown as CHAT.Task;

  it("返回最后一个 session_tasks，忽略其他类型", () => {
    const taskList = [
      task("tool_call", "t1"),
      task("session_tasks", "old"),
      task("tool_result", "t2"),
      task("session_tasks", "new"),
    ];

    expect(findLatestSessionTasks(undefined, taskList)?.messageId).toBe("new");
  });

  it("没有 session_tasks 时返回 undefined", () => {
    const taskList = [task("tool_call", "t1"), task("tool_result", "t2")];
    expect(findLatestSessionTasks(undefined, taskList)).toBeUndefined();
  });

  it("taskList 为空时回退到 chat.tasks 拍平结果", () => {
    const chat = {
      tasks: [
        [task("session_tasks", "from-chat")],
        [task("tool_call", "t1"), task("session_tasks", "from-chat-2")],
      ],
    } as unknown as CHAT.ChatItem;

    expect(findLatestSessionTasks(chat, [])?.messageId).toBe("from-chat-2");
  });

  it("两者都空时返回 undefined", () => {
    expect(findLatestSessionTasks(undefined, [])).toBeUndefined();
    expect(findLatestSessionTasks(undefined, undefined)).toBeUndefined();
  });
});

describe("asSessionTaskSnapshot", () => {
  it("恢复 bootstrap 返回的会话当前 Todo 快照", () => {
    const snapshot = asSessionTaskSnapshot({
      listId: "session-1",
      tasks: [
        { id: "1", subject: "进行中", status: "in_progress" },
        { id: "2", subject: "已完成", status: "completed" },
      ],
      total: 2,
      pending: 0,
      inProgress: 1,
      completed: 1,
    });

    expect(snapshot).toEqual({
      tasks: [
        {
          id: "1",
          subject: "进行中",
          description: undefined,
          status: "in_progress",
          activeForm: undefined,
          blockedBy: undefined,
        },
        {
          id: "2",
          subject: "已完成",
          description: undefined,
          status: "completed",
          activeForm: undefined,
          blockedBy: undefined,
        },
      ],
      total: 2,
      completed: 1,
      inProgress: 1,
    });
  });

  it("空列表不产生可见快照", () => {
    expect(
      asSessionTaskSnapshot({
        tasks: [],
        total: 0,
        completed: 0,
        inProgress: 0,
      })
    ).toBeNull();
  });
});

describe("resolveSessionTaskSnapshot", () => {
  it("实时空列表会覆盖刷新时恢复的旧快照", () => {
    const live = {
      messageType: "session_tasks",
      resultMap: {
        resultMap: {
          tasks: [],
          total: 0,
          completed: 0,
          inProgress: 0,
        },
      },
    } as unknown as CHAT.Task;

    expect(
      resolveSessionTaskSnapshot(live, {
        tasks: [{ id: "1", subject: "旧任务", status: "pending" }],
        total: 1,
        completed: 0,
        inProgress: 0,
      })
    ).toBeNull();
  });

  it("没有实时事件时使用刷新恢复的快照", () => {
    const snapshot = resolveSessionTaskSnapshot(null, {
      tasks: [{ id: "1", subject: "恢复任务", status: "pending" }],
      total: 1,
      completed: 0,
      inProgress: 0,
    });

    expect(snapshot?.tasks[0]?.subject).toBe("恢复任务");
  });
});
