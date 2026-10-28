import { describe, expect, it } from "vitest";

import { isWorkspaceResizeEventFor } from "./workspaceResize";

const asNode = (nodeType: number, contains: (other: unknown) => boolean) =>
  ({
    nodeType,
    contains
  }) as unknown as Element;

const eventFor = (target: unknown) => ({ target }) as unknown as Event;

describe("isWorkspaceResizeEventFor", () => {
  it("匹配 document 上派发的全局 resize 事件", () => {
    const node = asNode(1, () => false);
    const documentLike = asNode(9, () => true);

    expect(isWorkspaceResizeEventFor(eventFor(documentLike), node)).toBe(true);
  });

  it("匹配来自祖先节点的 resize 事件", () => {
    const node = asNode(1, () => false);
    const ancestor = asNode(1, (other) => other === node);

    expect(isWorkspaceResizeEventFor(eventFor(ancestor), node)).toBe(true);
  });

  it("匹配来自后代节点的 resize 事件", () => {
    const descendant = asNode(1, () => false);
    const node = asNode(1, (other) => other === descendant);

    expect(isWorkspaceResizeEventFor(eventFor(descendant), node)).toBe(true);
  });

  it("事件目标就是节点本身时也算命中", () => {
    const node = asNode(1, () => false);

    expect(isWorkspaceResizeEventFor(eventFor(node), node)).toBe(true);
  });

  it("无关节点和 window 之类的非 Node 目标返回 false", () => {
    const node = asNode(1, () => false);
    const unrelated = asNode(1, () => false);

    expect(isWorkspaceResizeEventFor(eventFor(unrelated), node)).toBe(false);
    expect(isWorkspaceResizeEventFor(eventFor({}), node)).toBe(false);
  });
});
