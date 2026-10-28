import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  createFollowController,
  FOLLOW_ATTEMPT_CAP,
  FOLLOW_BASE_DELAY_MS,
  FOLLOW_MAX_DELAY_MS,
} from "./followObservation";

describe("createFollowController", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("未调用时不排定时器", () => {
    const reconnect = vi.fn();
    createFollowController({
      reconnect,
      onExhausted: vi.fn()
    });

    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS * 2);

    expect(reconnect).not.toHaveBeenCalled();
  });

  it("scheduleReconnect 一次后按基础退避触发", () => {
    const reconnect = vi.fn();
    createFollowController({
      reconnect,
      onExhausted: vi.fn()
    }).scheduleReconnect();

    vi.advanceTimersByTime(FOLLOW_BASE_DELAY_MS - 1);
    expect(reconnect).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(reconnect).toHaveBeenCalledTimes(1);
  });

  it("连续重订的退避序列在 FOLLOW_MAX_DELAY_MS 封顶", () => {
    const reconnect = vi.fn();
    const follow = createFollowController({
      reconnect,
      onExhausted: vi.fn()
    });

    // 第 6 次（attempt=5）：800 * 2^5 = 25600，被 15000 压下来
    for (let i = 0; i < 5; i += 1) {
      follow.scheduleReconnect();
      vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS);
    }
    expect(FOLLOW_BASE_DELAY_MS * 2 ** 5).toBeGreaterThan(FOLLOW_MAX_DELAY_MS);

    follow.scheduleReconnect();
    // 未到封顶值不应触发
    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS - 1);
    expect(reconnect).toHaveBeenCalledTimes(5);

    vi.advanceTimersByTime(1);
    expect(reconnect).toHaveBeenCalledTimes(6);
    expect(follow.attempts()).toBe(6);

    // 第 7 次起封顶在 FOLLOW_MAX_DELAY_MS，不再变长
    follow.scheduleReconnect();
    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS - 1);
    expect(reconnect).toHaveBeenCalledTimes(6);
    vi.advanceTimersByTime(1);
    expect(reconnect).toHaveBeenCalledTimes(7);
  });

  it("显式延迟优先于指数退避", () => {
    const reconnect = vi.fn();
    const follow = createFollowController({
      reconnect,
      onExhausted: vi.fn()
    });

    follow.scheduleReconnect(300);

    vi.advanceTimersByTime(299);
    expect(reconnect).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(reconnect).toHaveBeenCalledTimes(1);
    // 显式延迟仍占一次 attempt
    expect(follow.attempts()).toBe(1);
  });

  it("达 cap 后触发 exhausted handler 且不再排定时器", () => {
    const onExhausted = vi.fn();
    const reconnect = vi.fn();
    const follow = createFollowController({
      reconnect,
      onExhausted
    });

    for (let i = 0; i < FOLLOW_ATTEMPT_CAP; i += 1) {
      follow.scheduleReconnect();
      vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS);
    }

    expect(onExhausted).not.toHaveBeenCalled();
    expect(follow.exhausted()).toBe(false);

    // 第 cap+1 次：熔断
    follow.scheduleReconnect();
    expect(onExhausted).toHaveBeenCalledTimes(1);
    expect(follow.exhausted()).toBe(true);

    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS * 2);
    expect(reconnect).toHaveBeenCalledTimes(FOLLOW_ATTEMPT_CAP);
  });

  it("noteProgress 后 attempts 归零，下次从基础退避重新开始", () => {
    const reconnect = vi.fn();
    const follow = createFollowController({
      reconnect,
      onExhausted: vi.fn()
    });

    follow.scheduleReconnect();
    vi.advanceTimersByTime(FOLLOW_BASE_DELAY_MS);
    follow.scheduleReconnect();
    vi.advanceTimersByTime(FOLLOW_BASE_DELAY_MS * 2);
    expect(follow.attempts()).toBe(2);

    follow.noteProgress();
    expect(follow.attempts()).toBe(0);

    follow.scheduleReconnect();
    vi.advanceTimersByTime(FOLLOW_BASE_DELAY_MS - 1);
    expect(reconnect).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(1);
    expect(reconnect).toHaveBeenCalledTimes(3);
  });

  it("dispose 清掉在途定时器，推进时间不再触发", () => {
    const reconnect = vi.fn();
    const follow = createFollowController({
      reconnect,
      onExhausted: vi.fn()
    });

    follow.scheduleReconnect();
    follow.dispose();

    vi.advanceTimersByTime(FOLLOW_MAX_DELAY_MS * 2);

    expect(reconnect).not.toHaveBeenCalled();
  });

  it("已有在途重订时忽略后续调用，避免同会话开出多条活流", () => {
    const reconnect = vi.fn();
    const follow = createFollowController({
      reconnect,
      onExhausted: vi.fn()
    });

    follow.scheduleReconnect(5000);
    follow.scheduleReconnect();
    follow.scheduleReconnect();

    vi.advanceTimersByTime(5000);
    expect(reconnect).toHaveBeenCalledTimes(1);
    expect(follow.attempts()).toBe(1);
  });
});
