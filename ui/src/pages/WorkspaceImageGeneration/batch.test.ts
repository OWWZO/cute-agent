import { describe, expect, it } from "vitest";

import {
  buildImageBatchPlans,
  runImageBatchRequests,
  shouldUseImageBatchMode,
} from "./batch";

describe("workspace image generation batch helpers", () => {
  it("returns batch mode when edits has multiple images and switch is enabled", () => {
    expect(
      shouldUseImageBatchMode({
        mode: "edits",
        imageCount: 3,
        batchMode: true,
      })
    ).toBe(true);
  });

  it("does not use batch mode for non-edits or single image", () => {
    expect(
      shouldUseImageBatchMode({
        mode: "images",
        imageCount: 3,
        batchMode: true,
      })
    ).toBe(false);
    expect(
      shouldUseImageBatchMode({
        mode: "edits",
        imageCount: 1,
        batchMode: true,
      })
    ).toBe(false);
  });

  it("splits multi-image edit request into one request per image", () => {
    const plans = buildImageBatchPlans({
      prompt: "统一改成电影海报",
      size: "1024x1024",
      images: [
        {
          fileName: "1.png",
          source: "data:image/png;base64,1",
          mask: ""
        },
        {
          fileName: "2.png",
          source: "data:image/png;base64,2",
          mask: "data:image/png;base64,mask"
        },
      ],
    });

    expect(plans).toHaveLength(2);
    expect(plans[0]).toMatchObject({
      key: "1",
      fileNames: ["data:image/png;base64,1"],
      maskFileNames: [""],
      fileName: "1",
    });
    expect(plans[1]).toMatchObject({
      key: "2",
      fileNames: ["data:image/png;base64,2"],
      maskFileNames: ["data:image/png;base64,mask"],
      fileName: "2",
    });
  });

  it("runs one request per batch plan and keeps per-item success and failure", async () => {
    const plans = buildImageBatchPlans({
      prompt: "统一改成电影海报",
      size: "1024x1024",
      images: [
        {
          fileName: "1.png",
          source: "data:image/png;base64,1",
          mask: ""
        },
        {
          fileName: "2.png",
          source: "data:image/png;base64,2",
          mask: ""
        },
      ],
    });

    const results = await runImageBatchRequests({
      prompt: "统一改成电影海报",
      size: "1024x1024",
      n: 1,
      plans,
      createRequestId: (index) => `req-${index + 1}`,
      request: async (payload) => {
        if (payload.fileName === "2") {
          throw new Error("第二张失败");
        }
        return {
          data: "ok",
          fileInfo: [],
          requestId: payload.requestId,
          usedFallback: false,
        };
      },
    });

    expect(results).toHaveLength(2);
    expect(results[0]).toMatchObject({
      success: true,
      index: 0,
      plan: { fileName: "1" },
      response: { requestId: "req-1" },
    });
    expect(results[1]).toMatchObject({
      success: false,
      index: 1,
      plan: { fileName: "2" },
    });
  });

  it("limits concurrent requests to two and preserves result order", async () => {
    const plans = buildImageBatchPlans({
      prompt: "统一改成电影海报",
      size: "1024x1024",
      images: Array.from({ length: 5 }, (_, index) => ({
        fileName: `${index + 1}.png`,
        source: `data:image/png;base64,${index + 1}`,
        mask: "",
      })),
    });

    let inFlight = 0;
    let maxInFlight = 0;
    const pendingResolvers: Array<() => void> = [];

    const runPromise = runImageBatchRequests({
      prompt: "统一改成电影海报",
      size: "1024x1024",
      n: 1,
      plans,
      createRequestId: (index) => `req-${index + 1}`,
      request: (payload) =>
        new Promise((resolve) => {
          inFlight += 1;
          maxInFlight = Math.max(maxInFlight, inFlight);
          pendingResolvers.push(() => {
            inFlight -= 1;
            resolve({
              data: payload.fileName,
              fileInfo: [],
              requestId: payload.requestId,
            });
          });
        }),
    });

    // 池子同步启动前两个 worker；后续只有前一单完成才会补位。
    expect(pendingResolvers).toHaveLength(2);

    while (pendingResolvers.length > 0) {
      pendingResolvers.shift()?.();
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }

    const results = await runPromise;
    expect(maxInFlight).toBe(2);
    expect(results.map((result) => result.index)).toEqual([0, 1, 2, 3, 4]);
    expect(results.every((result) => result.success)).toBe(true);
  });

  it("returns an empty list when there are no plans", async () => {
    const results = await runImageBatchRequests({
      prompt: "空批次",
      size: "1024x1024",
      n: 1,
      plans: [],
      createRequestId: () => "unused",
      request: async () => {
        throw new Error("不应被调用");
      },
    });

    expect(results).toEqual([]);
  });
});
