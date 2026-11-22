import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import ReplayDisclosure from "./ReplayDisclosure";

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
  readonly listeners = new Map<string, Array<(event: FakeEvent) => void>>();
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

  removeAttribute(key: string): void {
    delete this.attributes[key];
  }

  addEventListener(type: string, listener: (event: FakeEvent) => void): void {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type: string, listener: (event: FakeEvent) => void): void {
    const listeners = this.listeners.get(type) || [];
    this.listeners.set(type, listeners.filter((item) => item !== listener));
  }

  dispatchEvent(event: FakeEvent): boolean {
    event.target ||= this;
    for (const current of ancestorChain(event.target)) {
      if (current instanceof FakeElement) {
        event.currentTarget = current;
        for (const listener of current.listeners.get(event.type) || []) {
          listener(event);
        }
      }
      if (!event.bubbles || event.propagationStopped) break;
    }
    return !event.defaultPrevented;
  }

  click(): void {
    this.dispatchEvent(new FakeEvent("click", { bubbles: true, cancelable: true }));
  }

  getRootNode(): FakeElement {
    return ancestorChain(this).at(-1) as FakeElement;
  }

  get textContent(): string {
    return this.childNodes.map((child) => child.textContent).join("");
  }

  set textContent(value: string) {
    this.childNodes.length = 0;
    this.firstChild = null;
    if (value !== "") this.appendChild(new FakeText(value));
  }
}

class FakeEvent {
  target: FakeNode | null = null;
  currentTarget: FakeNode | null = null;
  defaultPrevented = false;
  propagationStopped = false;
  readonly timeStamp = Date.now();

  constructor(
    readonly type: string,
    readonly init: { bubbles?: boolean; cancelable?: boolean } = {}
  ) {}

  get bubbles(): boolean {
    return this.init.bubbles === true;
  }

  preventDefault(): void {
    this.defaultPrevented = true;
  }

  stopPropagation(): void {
    this.propagationStopped = true;
  }
}

type FakeNode = FakeElement | FakeText;

function ancestorChain(node: FakeNode | null): FakeNode[] {
  const chain: FakeNode[] = [];
  for (let current = node; current; current = current.parentNode) {
    chain.push(current);
  }
  return chain;
}

function installDom(): void {
  const fakeWindow = {
    document: null as unknown as FakeDocument,
    addEventListener() {},
    removeEventListener() {},
    navigator: { userAgent: "node" },
    location: { protocol: "http:" },
    HTMLIFrameElement: class {},
    setTimeout,
    clearTimeout,
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
    createElementNS: (_namespace: string, name: string) => new FakeElement(name),
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
  Object.defineProperty(globalThis, "document", {
    value: fakeDocument,
    configurable: true,
  });
  Object.defineProperty(globalThis, "window", {
    value: fakeWindow,
    configurable: true,
  });
  Object.defineProperty(globalThis, "navigator", {
    value: fakeWindow.navigator,
    configurable: true,
  });
  Object.defineProperty(globalThis, "HTMLElement", {
    value: FakeElement,
    configurable: true,
  });
  Object.defineProperty(globalThis, "Element", {
    value: FakeElement,
    configurable: true,
  });
  Object.defineProperty(globalThis, "Node", {
    value: FakeElement,
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
  createElementNS(namespace: string, name: string): FakeElement;
  createTextNode(text: string): FakeText;
  createComment(text: string): FakeText;
  addEventListener(): void;
  removeEventListener(): void;
  querySelectorAll(): unknown[];
};

function chat(replayLoaded = false): CHAT.ChatItem {
  return {
    sessionId: "sub-1",
    requestId: "run-1",
    query: "问题",
    files: [],
    forceStop: false,
    multiAgent: { tasks: [] },
    loading: false,
    tasks: [],
    replayAvailable: true,
    replayLoaded,
    metrics: { status: "STOPPED" },
  } as CHAT.ChatItem;
}

function findButton(root: FakeElement): FakeElement {
  const visit = (node: FakeNode): FakeElement | undefined => {
    if (node instanceof FakeElement && node.attributes["data-testid"] === "replay-disclosure") {
      return node;
    }
    for (const child of node.childNodes) {
      const hit = visit(child);
      if (hit) return hit;
    }
    return undefined;
  };
  const button = visit(root);
  if (!button) throw new Error("replay disclosure not found");
  return button;
}

describe("ReplayDisclosure lazy loading", () => {
  let root: Root | undefined;
  let container: FakeElement;

  beforeEach(() => {
    installDom();
    container = document.createElement("div") as unknown as FakeElement;
    root = createRoot(container as unknown as Element);
  });

  afterEach(async () => {
    await act(async () => root?.unmount());
    root = undefined;
  });

  it("first click only loads replay and opens it after loading", async () => {
    const onRequestReplay = vi.fn(() => Promise.resolve());
    const onToggle = vi.fn();
    const render = async (replayLoaded: boolean) => {
      await act(async () => {
        root!.render(
          <ReplayDisclosure
            chat={chat(replayLoaded)}
            onRequestReplay={onRequestReplay}
            open
            onToggle={onToggle}
          />
        );
      });
    };

    await render(false);
    findButton(container).click();
    expect(onRequestReplay).toHaveBeenCalledWith("run-1");
    expect(onToggle).not.toHaveBeenCalledWith(false);

    await render(true);
    expect(onToggle).toHaveBeenCalledWith(true);
  });

  it("loaded replay click only toggles and does not request again", async () => {
    const onRequestReplay = vi.fn();
    const onToggle = vi.fn();
    await act(async () => {
      root!.render(
        <ReplayDisclosure
          chat={chat(true)}
          onRequestReplay={onRequestReplay}
          open
          onToggle={onToggle}
        />
      );
    });

    findButton(container).click();
    expect(onRequestReplay).not.toHaveBeenCalled();
    expect(onToggle).toHaveBeenCalledWith(false);
  });

  it("failed loading can be retried by clicking again", async () => {
    const onRequestReplay = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce();
    await act(async () => {
      root!.render(
        <ReplayDisclosure
          chat={chat(false)}
          onRequestReplay={onRequestReplay}
          onToggle={vi.fn()}
        />
      );
    });

    findButton(container).click();
    await act(async () => {
      await Promise.resolve();
    });
    findButton(container).click();
    expect(onRequestReplay).toHaveBeenCalledTimes(2);
  });
});
