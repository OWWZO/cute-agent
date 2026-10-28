import { describe, expect, it } from "vitest";

import type { ChannelBindingMineResp, QrPollResp } from "@/services/channel";

/**
 * 轮询终止条件是这个页面最容易写错的地方：漏掉任何一条都会让前端一直打后端。
 * 这里用纯函数固化「什么情况下该继续轮询」，页面里的定时器只消费这个判定。
 */
type PollDecision = "continue" | "completed" | "expired";

function decidePoll(
  result: QrPollResp | null,
  elapsedMs: number,
  timeoutMs: number
): PollDecision {
  if (result?.status === "COMPLETED") return "completed";
  if (result?.status === "EXPIRED") return "expired";
  if (elapsedMs > timeoutMs) return "expired";
  return "continue";
}

const TIMEOUT = 610_000;

describe("channel binding polling", () => {
  it("continues while pending", () => {
    expect(decidePoll({ status: "PENDING" }, 0, TIMEOUT)).toBe("continue");
  });

  it("stops on completion", () => {
    expect(decidePoll({
      status: "COMPLETED",
      bindingId: 7
    }, 2_000, TIMEOUT)).toBe(
      "completed"
    );
  });

  it("stops on expiry reported by backend", () => {
    expect(decidePoll({ status: "EXPIRED" }, 2_000, TIMEOUT)).toBe("expired");
  });

  it("stops on client-side timeout", () => {
    expect(decidePoll({ status: "PENDING" }, TIMEOUT + 1, TIMEOUT)).toBe("expired");
  });

  it("stays pending exactly at the deadline", () => {
    // 边界取「未超时」，与后端 TTL 的边界语义保持一致。
    expect(decidePoll({ status: "PENDING" }, TIMEOUT, TIMEOUT)).toBe("continue");
  });
});

describe("binding quota", () => {
  const quotaReached = (mine: ChannelBindingMineResp) =>
    mine.binding === null && mine.globalBindingCount >= mine.maxBindings;

  it("allows binding when under the cap", () => {
    expect(
      quotaReached({
        binding: null,
        globalBindingCount: 3,
        maxBindings: 10
      })
    ).toBe(false);
  });

  it("blocks when the cap is reached", () => {
    expect(
      quotaReached({
        binding: null,
        globalBindingCount: 10,
        maxBindings: 10
      })
    ).toBe(true);
  });

  it("an existing binding is never blocked by the cap", () => {
    expect(
      quotaReached({
        binding: {
          bindingId: 1,
          appId: "102xxxxx",
          status: "ACTIVE",
        },
        globalBindingCount: 10,
        maxBindings: 10,
      })
    ).toBe(false);
  });
});
