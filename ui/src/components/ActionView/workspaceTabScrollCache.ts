/** 工作区标签页的临时阅读现场，只保存在页面内存中。 */
export type WorkspaceTabScrollSnapshot = {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
  ratio: number;
  anchorId: string;
  /** 锚点顶部相对滚动视口顶部的偏移。 */
  anchorOffset: number;
  atBottom: boolean;
  visibleTurnCount?: number;
  /** 子 Agent 各轮“展示过程”的展开态，用于恢复阅读现场的内容高度。 */
  processOpenByAnchorId?: Record<string, boolean>;
};

const cache = new Map<string, WorkspaceTabScrollSnapshot>();

function cacheKey(workspaceStateKey: string, tabKey: string): string {
  return `${workspaceStateKey}\u0000${tabKey}`;
}

export function readWorkspaceTabScroll(
  workspaceStateKey: string | undefined,
  tabKey: string | undefined
): WorkspaceTabScrollSnapshot | undefined {
  if (!workspaceStateKey || !tabKey) {
    return undefined;
  }
  return cache.get(cacheKey(workspaceStateKey, tabKey));
}

export function writeWorkspaceTabScroll(
  workspaceStateKey: string | undefined,
  tabKey: string | undefined,
  snapshot: WorkspaceTabScrollSnapshot
): void {
  if (!workspaceStateKey || !tabKey) {
    return;
  }
  const key = cacheKey(workspaceStateKey, tabKey);
  cache.delete(key);
  cache.set(key, { ...snapshot });
}

export function clearWorkspaceTabScroll(
  workspaceStateKey: string | undefined,
  tabKey?: string
): void {
  if (!workspaceStateKey) {
    return;
  }
  if (tabKey) {
    cache.delete(cacheKey(workspaceStateKey, tabKey));
    return;
  }
  const prefix = `${workspaceStateKey}\u0000`;
  for (const key of cache.keys()) {
    if (key.startsWith(prefix)) {
      cache.delete(key);
    }
  }
}

export function resetWorkspaceTabScrollCache(): void {
  cache.clear();
}
