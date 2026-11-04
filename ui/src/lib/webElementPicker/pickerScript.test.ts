import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { WebElementPickerScriptResult } from "./pickerScript";
import { buildWebElementPickerScript } from "./pickerScript";

class FakeStyle {
  cssText = "";
}

class FakeElement {
  tagName: string;
  nodeType = 1;
  parentElement: FakeElement | null = null;
  children: FakeElement[] = [];
  classList: string[] = [];
  style = new FakeStyle();
  textContent = "";
  isConnected = true;
  offsetWidth = 240;
  offsetHeight = 80;
  removed = false;
  private attributeMap = new Map<string, string>();

  constructor(tagName: string) {
    this.tagName = tagName.toUpperCase();
  }

  get id() {
    return this.getAttribute("id") ?? "";
  }

  get attributes() {
    return Array.from(this.attributeMap, ([name, value]) => ({ name, value }));
  }

  get innerText() {
    return this.textContent;
  }

  get outerHTML() {
    const attrs = this.attributes.map(({ name, value }) => ` ${name}="${value}"`).join("");
    return `<${this.tagName.toLowerCase()}${attrs}>${this.textContent}</${this.tagName.toLowerCase()}>`;
  }

  setAttribute(name: string, value: string) {
    this.attributeMap.set(name, value);
  }

  getAttribute(name: string) {
    return this.attributeMap.get(name) ?? null;
  }

  hasAttribute(name: string) {
    return this.attributeMap.has(name);
  }

  removeAttribute(name: string) {
    this.attributeMap.delete(name);
  }

  append(...nodes: FakeElement[]) {
    this.children.push(...nodes);
    nodes.forEach((node) => {
      node.parentElement = this;
    });
  }

  remove() {
    this.removed = true;
    if (this.parentElement) {
      this.parentElement.children = this.parentElement.children.filter((child) => child !== this);
    }
  }

  contains(node: FakeElement) {
    return node === this || this.children.includes(node);
  }

  replaceChildren() {
    this.children = [];
  }

  querySelectorAll() {
    return [] as FakeElement[];
  }

  cloneNode() {
    const clone = new FakeElement(this.tagName);
    this.attributeMap.forEach((value, name) => clone.setAttribute(name, value));
    clone.textContent = this.textContent;
    return clone;
  }

  closest() {
    return this;
  }

  getBoundingClientRect() {
    return {
      x: 10,
      y: 20,
      left: 10,
      top: 20,
      right: 110,
      bottom: 60,
      width: 100,
      height: 40,
    } as DOMRect;
  }
}

class FakeInputElement extends FakeElement {
  type = "text";

  constructor() {
    super("input");
  }
}

class FakeTextAreaElement extends FakeElement {
  constructor() {
    super("textarea");
  }
}

type Listener = (event: Record<string, unknown>) => void;

function createPickerHarness() {
  const listeners = new Map<string, Listener>();
  const documentElement = new FakeElement("html");
  const fakeDocument = {
    title: "Demo",
    documentElement,
    createElement: (tagName: string) => new FakeElement(tagName),
    getElementById: () => null,
    addEventListener: (type: string, listener: Listener) => listeners.set(type, listener),
    removeEventListener: (type: string, listener: Listener) => {
      if (listeners.get(type) === listener) listeners.delete(type);
    },
  };
  const fakeWindow = {
    innerWidth: 1280,
    innerHeight: 720,
    CSS: { escape: (value: string) => value },
    getComputedStyle: () => ({
      backgroundColor: "rgb(255, 255, 255)",
      color: "rgb(13, 13, 13)",
      display: "block",
      fontFamily: "Inter",
      fontSize: "14px",
      fontWeight: "400",
    }),
  };

  vi.stubGlobal("Element", FakeElement);
  vi.stubGlobal("HTMLInputElement", FakeInputElement);
  vi.stubGlobal("HTMLTextAreaElement", FakeTextAreaElement);
  vi.stubGlobal("Node", { ELEMENT_NODE: 1 });
  vi.stubGlobal("location", { href: "http://localhost:3000/demo" });

  const run = () => {
    const source = `return ${buildWebElementPickerScript()}`;
    const execute = new Function("window", "document", source) as (
      window: typeof fakeWindow,
      document: typeof fakeDocument
    ) => Promise<WebElementPickerScriptResult>;
    return execute(fakeWindow, fakeDocument);
  };

  return {
    listeners,
    documentElement,
    run,
  };
}

function clickEvent(target: FakeElement) {
  return {
    target,
    preventDefault: vi.fn(),
    stopPropagation: vi.fn(),
    stopImmediatePropagation: vi.fn(),
  };
}

describe("webElementPickerScript", () => {
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("清理选中根节点自身的表单值", async () => {
    const harness = createPickerHarness();
    const resultPromise = harness.run();
    const selected = new FakeInputElement();
    selected.setAttribute("id", "password");
    selected.setAttribute("value", "super-secret");
    const clone = new FakeInputElement();
    clone.setAttribute("value", "super-secret");
    selected.cloneNode = () => clone;

    harness.listeners.get("click")?.(clickEvent(selected));
    const result = await resultPromise;

    expect(result.status).toBe("selected");
    if (result.status === "selected") {
      const excerpt = String(result.element.htmlExcerpt ?? "");
      expect(excerpt.includes("super-secret")).toBe(false);
      expect(excerpt.includes("<input")).toBe(true);
    }
  });

  it("采集抛错时结束选择器并清理监听", async () => {
    const harness = createPickerHarness();
    const resultPromise = harness.run();
    const selected = new FakeElement("button");
    selected.getBoundingClientRect = () => {
      throw new Error("detached");
    };

    harness.listeners.get("click")?.(clickEvent(selected));

    await expect(resultPromise).resolves.toEqual({ status: "cancelled" });
    expect(harness.listeners.size).toBe(0);
    expect(harness.documentElement.children.every((child) => child.removed)).toBe(true);
  });

  it("使用点击目标而不是上一次 hover 命中项", async () => {
    const harness = createPickerHarness();
    const resultPromise = harness.run();
    const selected = new FakeElement("button");
    selected.setAttribute("id", "clicked");

    harness.listeners.get("click")?.(clickEvent(selected));
    const result = await resultPromise;

    expect(result).toMatchObject({
      status: "selected",
      element: { tagName: "button", selector: "#clicked" },
    });
  });
});
