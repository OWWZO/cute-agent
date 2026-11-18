import { beforeEach, describe, expect, it } from "vitest";

import {
  SESSION_TIMELINE_CACHE_LIMIT,
  clearSessionTimelineCache,
  readSessionTimelineCache,
  writeSessionTimelineCache,
} from "./sessionTimelineCache";
import type { SessionTimelineState } from "./useSessionTimeline";

const state = {
  ready: true,
  loading: false,
  error: "",
  history: null,
  runs: {
    "r-1": {
      items: [],
      status: "SUCCESS",
      loaded: true,
    },
  },
  activeRequestId: "r-1",
  source: "history",
  reconnectAttempts: 0,
  reconnectExhausted: false,
} satisfies SessionTimelineState;

describe("sessionTimelineCache", () => {
  beforeEach(() => {
    clearSessionTimelineCache();
  });

  it("ready 状态按 sessionId 缓存，未 ready 不缓存", () => {
    writeSessionTimelineCache("sub-1", state);
    writeSessionTimelineCache("sub-2", { ...state, ready: false });

    expect(readSessionTimelineCache("sub-1")?.activeRequestId).toBe("r-1");
    expect(readSessionTimelineCache("sub-2")).toBeUndefined();
  });

  it("读取返回快照副本，不暴露内部 runs 对象", () => {
    writeSessionTimelineCache("sub-1", state);
    const hit = readSessionTimelineCache("sub-1")!;
    hit.runs["r-2"] = { items: [], status: "", loaded: false };

    expect(readSessionTimelineCache("sub-1")!.runs["r-2"]).toBeUndefined();
  });

  it("可按 sessionId 清理", () => {
    writeSessionTimelineCache("sub-1", state);
    clearSessionTimelineCache("sub-1");
    expect(readSessionTimelineCache("sub-1")).toBeUndefined();
  });

  it("读取命中会刷新 LRU，超过容量淘汰最久未使用的条目", () => {
    for (let i = 1; i <= SESSION_TIMELINE_CACHE_LIMIT; i += 1) {
      writeSessionTimelineCache(`sub-${i}`, state);
    }

    expect(readSessionTimelineCache("sub-1")).toBeDefined();
    writeSessionTimelineCache(
      `sub-${SESSION_TIMELINE_CACHE_LIMIT + 1}`,
      state
    );

    expect(readSessionTimelineCache("sub-1")).toBeDefined();
    expect(readSessionTimelineCache("sub-2")).toBeUndefined();
  });

  it("重复写入已有 session 也算最近使用", () => {
    for (let i = 1; i <= SESSION_TIMELINE_CACHE_LIMIT; i += 1) {
      writeSessionTimelineCache(`sub-${i}`, state);
    }

    writeSessionTimelineCache("sub-1", state);
    writeSessionTimelineCache(
      `sub-${SESSION_TIMELINE_CACHE_LIMIT + 1}`,
      state
    );

    expect(readSessionTimelineCache("sub-1")).toBeDefined();
    expect(readSessionTimelineCache("sub-2")).toBeUndefined();
  });
});
