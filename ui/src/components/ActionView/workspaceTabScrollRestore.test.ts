import { describe, expect, it } from "vitest";

import {
  createScrollSnapshot,
  resolveRestoreScrollTop,
} from "./workspaceTabScrollRestore";

type Anchor = {
  id: string;
  top: number;
};

function container(
  scrollTop = 0,
  scrollHeight = 1000,
  anchors: Anchor[] = []
) {
  return {
    scrollTop,
    scrollHeight,
    clientHeight: 300,
    getBoundingClientRect: () => ({ top: 0 }),
    querySelectorAll: () =>
      anchors.map((anchor) => ({
        dataset: { scrollAnchorId: anchor.id },
        getBoundingClientRect: () => ({ top: anchor.top }),
      })),
  };
}

describe("workspaceTabScrollRestore", () => {
  it("记录连续滚动位置、比例和是否在底部", () => {
    const el = container(350);
    expect(
      createScrollSnapshot(el, 8, { "turn:r1": true })
    ).toMatchObject({
      scrollTop: 350,
      scrollHeight: 1000,
      clientHeight: 300,
      ratio: 0.5,
      atBottom: false,
      visibleTurnCount: 8,
      processOpenByAnchorId: { "turn:r1": true },
    });

    el.scrollTop = 680;
    expect(createScrollSnapshot(el).atBottom).toBe(true);
  });

  it("可只记录轻量滚动现场，不扫描锚点", () => {
    let anchorScans = 0;
    const el = container(350, 1000, [{ id: "tool-42", top: 420 }]);
    el.querySelectorAll = () => {
      anchorScans += 1;
      return [];
    };

    const snapshot = createScrollSnapshot(el, 8, { "turn:r1": true }, {
      includeAnchor: false,
      includeProcessState: false,
    });
    expect(snapshot).toMatchObject({
      scrollTop: 350,
      anchorId: "",
      anchorOffset: 0,
    });
    expect(snapshot.processOpenByAnchorId).toBeUndefined();
    expect(anchorScans).toBe(0);
  });

  it("锚点存在时按锚点恢复", () => {
    const el = container(0, 1000, [{ id: "tool-42", top: 420 }]);

    const target = resolveRestoreScrollTop(el, {
      scrollTop: 400,
      scrollHeight: 1000,
      clientHeight: 300,
      ratio: 0.5,
      anchorId: "tool-42",
      anchorOffset: 8,
      atBottom: false,
    });
    expect(target).toBe(412);
  });

  it("锚点恢复会考虑容器当前滚动位置", () => {
    const el = container(120, 1000, [{ id: "tool-42", top: 420 }]);

    const target = resolveRestoreScrollTop(el, {
      scrollTop: 320,
      scrollHeight: 1000,
      clientHeight: 300,
      ratio: 320 / 700,
      anchorId: "tool-42",
      anchorOffset: 8,
      atBottom: false,
    });
    expect(target).toBe(532);
  });

  it("内容高度变化后仍能把锚点放回原来的屏幕位置", () => {
    const before = container(320, 1000, [{ id: "tool-42", top: 420 }]);
    const snapshot = createScrollSnapshot(before);
    const after = container(0, 1200, [{ id: "tool-42", top: 740 }]);

    expect(resolveRestoreScrollTop(after, snapshot)).toBe(320);
  });

  it("锚点丢失时按原比例恢复；高度未变时比例等价于像素位置", () => {
    const el = container();
    expect(
      resolveRestoreScrollTop(el, {
        scrollTop: 450,
        scrollHeight: 1000,
        clientHeight: 300,
        ratio: 450 / 700,
        anchorId: "missing",
        anchorOffset: 0,
        atBottom: false,
      })
    ).toBe(450);
  });

  it("内容高度变化且锚点丢失时按原比例恢复", () => {
    const el = container(0, 2000);
    expect(
      resolveRestoreScrollTop(el, {
        scrollTop: 350,
        scrollHeight: 1000,
        clientHeight: 300,
        ratio: 0.5,
        anchorId: "",
        anchorOffset: 0,
        atBottom: false,
      })
    ).toBe(850);
  });

  it("无效比例与不可滚动内容都安全", () => {
    const el = container(0, 300);
    expect(
      resolveRestoreScrollTop(el, {
        scrollTop: 10,
        scrollHeight: 300,
        clientHeight: 300,
        ratio: 0,
        anchorId: "",
        anchorOffset: 0,
        atBottom: true,
      })
    ).toBe(0);
  });
});
