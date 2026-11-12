import React, { act, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, it } from "vitest";

import { syncSelectedDraft } from "./draftSyncEffect";

type Draft = { isNew: boolean; agentKey: string; systemPrompt: string };

const selectedDraft: Draft = {
  isNew: false,
  agentKey: "researcher",
  systemPrompt: "服务端提示词",
};

/** 仓库没有 DOM 测试库；用 React 自带的 act 和最小 DOM 桩提供 renderHook。 */
class FakeText {
  readonly nodeType = 3;
  readonly nodeName = "#text";
  readonly childNodes: FakeNode[] = [];
  parentNode: FakeNode | null = null;

  constructor(public data: string) {}

  get textContent(): string {
    return this.data;
  }

  set textContent(value: string) {
    this.data = value;
  }
}

class FakeElement {
  readonly nodeType = 1;
  readonly tagName: string;
  readonly namespaceURI = "http://www.w3.org/1999/xhtml";
  readonly childNodes: FakeNode[] = [];
  readonly style = {};
  readonly attributes: Record<string, string> = {};
  readonly ownerDocument = globalThis.document;
  parentNode: FakeNode | null = null;
  firstChild: FakeNode | null = null;

  constructor(readonly nodeName: string) {
    this.tagName = nodeName.toUpperCase();
  }

  appendChild(child: FakeNode): FakeNode {
    this.childNodes.push(child);
    child.parentNode = this;
    this.firstChild = this.childNodes[0];
    return child;
  }

  insertBefore(child: FakeNode, ref: FakeNode | null): FakeNode {
    const index = ref ? this.childNodes.indexOf(ref) : -1;
    if (index < 0) this.childNodes.push(child);
    else this.childNodes.splice(index, 0, child);
    child.parentNode = this;
    this.firstChild = this.childNodes[0];
    return child;
  }

  removeChild(child: FakeNode): FakeNode {
    const index = this.childNodes.indexOf(child);
    if (index >= 0) this.childNodes.splice(index, 1);
    child.parentNode = null;
    this.firstChild = this.childNodes[0] || null;
    return child;
  }

  setAttribute(key: string, value: string): void {
    this.attributes[key] = value;
  }

  addEventListener(): void {}
  removeEventListener(): void {}

  get textContent(): string {
    return this.childNodes.map((child) => child.textContent).join("");
  }

  set textContent(value: string) {
    this.childNodes.length = 0;
    this.firstChild = null;
    if (value !== "") this.appendChild(new FakeText(value));
  }
}

type FakeNode = FakeElement | FakeText;

function installDom(): void {
  const fakeWindow = {
    document: null as unknown as FakeDocument,
    addEventListener() {},
    removeEventListener() {},
    navigator: { userAgent: "node" },
    location: { protocol: "http:" },
    HTMLIFrameElement: class {},
  };
  const fakeDocument: FakeDocument = {
    nodeType: 9,
    nodeName: "#document",
    documentElement: new FakeElement("html"),
    body: new FakeElement("body"),
    defaultView: fakeWindow,
    HTMLIFrameElement: class {},
    activeElement: null,
    createElement: (name) => new FakeElement(name),
    createTextNode: (text) => new FakeText(text),
    createComment: (text) => new FakeText(text),
    addEventListener() {},
    removeEventListener() {},
    querySelectorAll: () => [],
  };
  fakeWindow.document = fakeDocument;

  Object.defineProperty(globalThis, "IS_REACT_ACT_ENVIRONMENT", {
    value: true,
    configurable: true,
  });
  Object.defineProperty(globalThis, "document", { value: fakeDocument, configurable: true });
  Object.defineProperty(globalThis, "window", { value: fakeWindow, configurable: true });
  Object.defineProperty(globalThis, "navigator", { value: fakeWindow.navigator, configurable: true });
  Object.defineProperty(globalThis, "HTMLElement", { value: FakeElement, configurable: true });
  Object.defineProperty(globalThis, "Element", { value: FakeElement, configurable: true });
  Object.defineProperty(globalThis, "Node", { value: FakeElement, configurable: true });
  Object.defineProperty(globalThis, "MutationObserver", {
    value: class { observe() {} disconnect() {} },
    configurable: true,
  });
  Object.defineProperty(globalThis, "requestAnimationFrame", {
    value: (callback: () => void) => setTimeout(callback, 0),
    configurable: true,
  });
  Object.defineProperty(globalThis, "cancelAnimationFrame", {
    value: clearTimeout,
    configurable: true,
  });
}

type FakeDocument = {
  nodeType: number;
  nodeName: string;
  documentElement: FakeElement;
  body: FakeElement;
  defaultView: unknown;
  HTMLIFrameElement: unknown;
  activeElement: unknown;
  createElement(name: string): FakeElement;
  createTextNode(text: string): FakeText;
  createComment(text: string): FakeText;
  addEventListener(): void;
  removeEventListener(): void;
  querySelectorAll(): unknown[];
};

function renderHook<T>(useHook: () => T) {
  const result = {
    current: undefined as T,
    renders: 0,
  };
  const container = document.createElement("div");
  const root = createRoot(container as unknown as Element);

  function Probe() {
    result.renders += 1;
    result.current = useHook();
    return null;
  }

  return {
    result,
    async render() {
      await act(async () => {
        root.render(React.createElement(Probe));
      });
    },
    async unmount() {
      await act(async () => root.unmount());
    },
  };
}

describe("子 Agent 草稿同步 effect", () => {
  it("选中定义后渲染次数有限收敛", async () => {
    installDom();
    const root = renderHook(() => {
      const [draft, setDraft] = useState<Draft>({
        isNew: true,
        agentKey: "",
        systemPrompt: "",
      });
      const lastSyncedRef = React.useRef<{
        key: string;
        draft: Draft;
      } | null>(null);
      useEffect(() => {
        syncSelectedDraft(
          draft,
          setDraft,
          lastSyncedRef,
          "researcher",
          { ...selectedDraft },
        );
      });
      return draft;
    });

    await root.render();
    expect(root.result.current).toEqual(selectedDraft);
    expect(root.result.renders).toBeLessThan(10);
    await root.unmount();
  });

  it("同一内容不重复写入新对象", async () => {
    installDom();
    const root = renderHook(() => {
      const [draft, setDraft] = useState<Draft>(selectedDraft);
      const lastSyncedRef = React.useRef({
        key: "researcher",
        draft: selectedDraft,
      });
      useEffect(() => {
        syncSelectedDraft(
          draft,
          setDraft,
          lastSyncedRef,
          "researcher",
          { ...selectedDraft },
        );
      }, [draft]);
      return draft;
    });

    await root.render();
    expect(root.result.current).toBe(selectedDraft);
    expect(root.result.renders).toBe(1);
    await root.unmount();
  });

  it("点选写入的草稿内容相同时仍记住当前选中项", async () => {
    installDom();
    const lastSyncedRef: {
      current: { key: string; draft: Draft } | null;
    } = { current: null };
    const root = renderHook(() => {
      const [draft, setDraft] = useState<Draft>(selectedDraft);
      useEffect(() => {
        syncSelectedDraft(
          draft,
          setDraft,
          lastSyncedRef,
          "researcher",
          { ...selectedDraft },
        );
      }, [draft]);
      return draft;
    });

    await root.render();
    expect(lastSyncedRef.current).toEqual({
      key: "researcher",
      draft: selectedDraft,
    });
    await root.unmount();
  });

  it("草稿未修改时允许内容不同的服务端快照回填", async () => {
    installDom();
    const root = renderHook(() => {
      const [draft, setDraft] = useState<Draft>(selectedDraft);
      const lastSyncedRef = React.useRef({
        key: "researcher",
        draft: selectedDraft,
      });
      useEffect(() => {
        syncSelectedDraft(
          draft,
          setDraft,
          lastSyncedRef,
          "researcher",
          { ...selectedDraft, systemPrompt: "服务端新提示词" },
        );
      }, [draft]);
      return draft;
    });

    await root.render();
    expect(root.result.current.systemPrompt).toBe("服务端新提示词");
    expect(root.result.renders).toBeLessThan(10);
    await root.unmount();
  });
});
