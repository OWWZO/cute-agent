import { describe, expect, it, vi } from "vitest";
import TypeWriterCore from "./TypeWriterCore";

const create = (maxStepSeconds?: number) =>
  new TypeWriterCore({
    onConsume: () => undefined,
    maxStepSeconds
  });

describe("TypeWriterCore", () => {
  it("非法 maxStepSeconds 回落到默认 50ms", () => {
    expect(create(0).maxStepSeconds).toBe(50);
    expect(create(-1).maxStepSeconds).toBe(50);
    expect(create(Number.NaN).maxStepSeconds).toBe(50);
  });

  it("合法的 maxStepSeconds 正常生效", () => {
    expect(create(30).maxStepSeconds).toBe(30);
  });

  // 回归：队列很长时 2000 / length 取整可能为 0，
  // 0ms 递归 setTimeout 会把主线程卡死。
  it("超长队列下动态间隔也不为 0", () => {
    const writer = create();
    writer.add("x".repeat(5000));

    expect(writer.dynamicSpeed()).toBeGreaterThanOrEqual(1);
  });

  it("短队列按队列长度动态加速", () => {
    const writer = create();
    writer.add("x".repeat(100));

    expect(writer.dynamicSpeed()).toBe(20);
  });

  // 回归：清理后必须把句柄复位为 undefined，否则残留的旧 id 会让状态判断失真。
  it("清理后复位定时器句柄", () => {
    vi.useFakeTimers();
    try {
      const writer = create();
      writer.add("abc");
      writer.start();
      expect(writer.timer).toBeDefined();

      writer.onRendered();
      expect(writer.timer).toBeUndefined();

      writer.start();
      writer.onClearQueueList();
      expect(writer.timer).toBeUndefined();
      expect(writer.queueList).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });
});
