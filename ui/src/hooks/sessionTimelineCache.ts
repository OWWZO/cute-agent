import type { SessionTimelineState } from "./useSessionTimeline";

export const SESSION_TIMELINE_CACHE_LIMIT = 12;

const cache = new Map<string, SessionTimelineState>();

function cloneState(state: SessionTimelineState): SessionTimelineState {
  return {
    ...state,
    history: state.history,
    runs: { ...state.runs },
  };
}

export function readSessionTimelineCache(
  sessionId: string | undefined
): SessionTimelineState | undefined {
  if (!sessionId) {
    return undefined;
  }
  const hit = cache.get(sessionId);
  if (!hit) {
    return undefined;
  }
  // Map 的插入顺序就是 LRU 顺序；命中时移到末尾。
  cache.delete(sessionId);
  cache.set(sessionId, hit);
  return cloneState(hit);
}

export function writeSessionTimelineCache(
  sessionId: string | undefined,
  state: SessionTimelineState
): void {
  if (!sessionId || !state.ready) {
    return;
  }
  cache.delete(sessionId);
  cache.set(sessionId, cloneState(state));
  while (cache.size > SESSION_TIMELINE_CACHE_LIMIT) {
    const oldest = cache.keys().next().value;
    if (oldest === undefined) {
      break;
    }
    cache.delete(oldest);
  }
}

export function clearSessionTimelineCache(sessionId?: string): void {
  if (!sessionId) {
    cache.clear();
    return;
  }
  cache.delete(sessionId);
}
