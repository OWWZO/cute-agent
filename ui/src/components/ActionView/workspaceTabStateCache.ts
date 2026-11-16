import type { WorkspaceTab } from "./workspaceFiles";

export type WorkspaceTabViewMode = "preview" | "source";

export type WorkspaceTabStateSnapshot = {
  tabs: WorkspaceTab[];
  selectedKey: string;
  viewMode: WorkspaceTabViewMode;
};

const MAX_CACHED_SESSIONS = 20;
const cache = new Map<string, WorkspaceTabStateSnapshot>();

const emptyState = (): WorkspaceTabStateSnapshot => ({
  tabs: [],
  selectedKey: "",
  viewMode: "preview",
});

/**
 * 工作区收起时 ActionView 会随面板卸载。把已打开 tab 的轻量身份缓存下来，
 * 重新展开时恢复，避免「关闭工作区再打开」只剩一块空白面板。
 */
export function readWorkspaceTabState(
  key?: string
): WorkspaceTabStateSnapshot {
  if (!key) return emptyState();
  const hit = cache.get(key);
  if (!hit) return emptyState();

  // Map 插入顺序兼作简易 LRU：命中后移到末尾。
  cache.delete(key);
  cache.set(key, hit);
  return {
    tabs: [...hit.tabs],
    selectedKey: hit.selectedKey,
    viewMode: hit.viewMode,
  };
}

export function rememberWorkspaceTabState(
  key: string | undefined,
  snapshot: WorkspaceTabStateSnapshot
) {
  if (!key) return;
  cache.delete(key);
  if (snapshot.tabs.length === 0) return;

  cache.set(key, {
    tabs: [...snapshot.tabs],
    selectedKey: snapshot.selectedKey,
    viewMode: snapshot.viewMode,
  });
  while (cache.size > MAX_CACHED_SESSIONS) {
    const oldest = cache.keys().next().value;
    if (oldest === undefined) break;
    cache.delete(oldest);
  }
}

export function resetWorkspaceTabStateCache() {
  cache.clear();
}
