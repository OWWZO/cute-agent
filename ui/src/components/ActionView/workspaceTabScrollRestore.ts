import type { WorkspaceTabScrollSnapshot } from "./workspaceTabScrollCache";

const BOTTOM_THRESHOLD = 32;

type RectLike = { top: number };
type AnchorLike = {
  dataset?: { scrollAnchorId?: string };
  getBoundingClientRect(): RectLike;
};
type ScrollContainerLike = {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
  getBoundingClientRect(): RectLike;
  querySelectorAll?: (selector: string) => ArrayLike<AnchorLike>;
};

function anchorsOf(element: ScrollContainerLike): AnchorLike[] {
  const nodes = element.querySelectorAll?.("[data-scroll-anchor-id]");
  return nodes ? Array.from(nodes) : [];
}

export function createScrollSnapshot(
  element: ScrollContainerLike,
  visibleTurnCount?: number,
  processOpenByAnchorId?: Record<string, boolean>,
  options: { includeAnchor?: boolean; includeProcessState?: boolean } = {}
): WorkspaceTabScrollSnapshot {
  const scrollTop = Math.max(0, element.scrollTop);
  const scrollHeight = Math.max(0, element.scrollHeight);
  const clientHeight = Math.max(0, element.clientHeight);
  const maxScroll = Math.max(0, scrollHeight - clientHeight);
  const anchored =
    options.includeAnchor === false ? undefined : findAnchor(element);
  return {
    scrollTop,
    scrollHeight,
    clientHeight,
    ratio: maxScroll > 0 ? scrollTop / maxScroll : 0,
    anchorId: anchored?.id || "",
    anchorOffset: anchored?.offset || 0,
    atBottom: maxScroll - scrollTop < BOTTOM_THRESHOLD,
    ...(visibleTurnCount === undefined ? {} : { visibleTurnCount }),
    ...(processOpenByAnchorId === undefined ||
      options.includeProcessState === false
      ? {}
      : { processOpenByAnchorId: { ...processOpenByAnchorId } }),
  };
}

export function findAnchor(
  element: ScrollContainerLike
): { id: string; offset: number } | undefined {
  const viewportTop = element.getBoundingClientRect().top;
  let best: { id: string; offset: number; distance: number } | undefined;
  for (const candidate of anchorsOf(element)) {
    const distance = candidate.getBoundingClientRect().top - viewportTop;
    if (distance < -BOTTOM_THRESHOLD) {
      continue;
    }
    if (!best || distance < best.distance) {
      best = {
        id: candidate.dataset?.scrollAnchorId || "",
        offset: distance,
        distance,
      };
    }
  }
  return best && best.id ? { id: best.id, offset: best.offset } : undefined;
}

export function resolveRestoreScrollTop(
  element: ScrollContainerLike,
  snapshot: WorkspaceTabScrollSnapshot
): number {
  const maxScroll = Math.max(0, element.scrollHeight - element.clientHeight);
  if (maxScroll === 0) {
    return 0;
  }

  if (snapshot.anchorId) {
    const anchor = anchorsOf(element).find(
      (candidate) => candidate.dataset?.scrollAnchorId === snapshot.anchorId
    );
    if (anchor) {
      const viewportTop = element.getBoundingClientRect().top;
      const anchorTop = anchor.getBoundingClientRect().top - viewportTop;
      // anchorOffset 是锚点相对滚动视口顶部的偏移；先把锚点换算成内容坐标，
      // 再减去原偏移，才能把同一个锚点放回相同的屏幕位置。
      return clamp(
        element.scrollTop + anchorTop - snapshot.anchorOffset,
        maxScroll
      );
    }
  }

  return Math.round(clamp(snapshot.ratio * maxScroll, maxScroll));
}

function clamp(value: number, max: number): number {
  return Math.min(Math.max(value, 0), max);
}
