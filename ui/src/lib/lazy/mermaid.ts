export type MermaidModule = typeof import("mermaid");

let mermaidPromise: Promise<MermaidModule> | undefined;

export function loadMermaid(): Promise<MermaidModule> {
  return (mermaidPromise ??= import("mermaid").catch((error: unknown) => {
    // 失败的 chunk 不缓存，否则用户点「重试」永远拿到同一个 rejection。
    mermaidPromise = undefined;
    throw error;
  }));
}
