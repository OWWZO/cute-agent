import { describe, expect, it } from "vitest";

import { resolveToolKey } from "@/components/Dialogue/tools/toolTaskAdapter";
import { canonicalizeDeepSearchTask } from "./deepSearchCanonicalizer";
import { getTaskRenderSignature, processTaskForRender } from "./renderTasks";

describe("DeepSearch 展示任务拆分", () => {
  it("章节内有多个查询词时仍按章节生成展示卡", () => {
    const task = {
      messageType: "deep_search",
      messageTime: "1714041600000",
      resultMap: {
        messageType: "chapter_summary",
        searchResult: {
          query: ["一章查询一", "一章查询二", "一章查询三", "二章查询一", "二章查询二", "二章查询三"],
          docs: [],
        },
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["一章查询一", "一章查询二", "一章查询三"],
            docs: [[]],
            summary: "第一章总结",
          },
          C2: {
            chapterId: "C2",
            chapterTitle: "第二章",
            chapterOrder: 2,
            queries: ["二章查询一", "二章查询二", "二章查询三"],
            docs: [[]],
            summary: "第二章总结",
          },
        },
      },
    } as unknown as CHAT.Task;

    const rendered = processTaskForRender(task, "deep-search-");

    expect(rendered).toHaveLength(2);
    expect(rendered.map((item) => item.resultMap?.chapterId)).toEqual(["C1", "C2"]);
    expect(rendered.map((item) => item.resultMap?.chapterTitle)).toEqual([
      "第一章",
      "第二章",
    ]);
  });

  it("搜索早期只有 searchResult.chapters 时也按章节生成卡片", () => {
    const task = {
      messageType: "deep_search",
      messageTime: "1714041600001",
      resultMap: {
        messageType: "search",
        searchResult: {
          query: ["查询一", "查询二", "查询三", "查询四"],
          docs: [
            [
              {
                link: "a",
                title: "来源一",
              },
            ],
            [],
            [
              {
                link: "b",
                title: "来源二",
              },
            ],
            [],
          ],
          chapters: [
            {
              chapterId: "C1",
              chapterTitle: "第一章",
              chapterOrder: 1,
            },
            {
              chapterId: "C2",
              chapterTitle: "第二章",
              chapterOrder: 2,
            },
          ],
        },
      },
    } as unknown as CHAT.Task;

    const rendered = processTaskForRender(task, "deep-search-early-");

    expect(rendered).toHaveLength(2);
    expect(rendered.map((item) => item.resultMap?.chapterId)).toEqual(["C1", "C2"]);
  });

  it("章节重排不会改变已有卡片的 React 身份", () => {
    const makeTask = (c1Order: number, c2Order: number) => ({
      messageType: "deep_search",
      messageTime: "1714041600002",
      resultMap: {
        messageType: "chapter_summary",
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: c1Order,
            queries: ["查询一"],
            summary: "第一章总结",
          },
          C2: {
            chapterId: "C2",
            chapterTitle: "第二章",
            chapterOrder: c2Order,
            queries: ["查询二"],
            summary: "第二章总结",
          },
        },
      },
    }) as unknown as CHAT.Task;

    const before = processTaskForRender(makeTask(1, 2), "deep-search-stable-");
    const after = processTaskForRender(makeTask(2, 1), "deep-search-stable-");
    const idOf = (items: CHAT.Task[], chapterId: string) =>
      items.find((item) => item.resultMap?.chapterId === chapterId)?.id;

    expect(idOf(before, "C1")).toBe(idOf(after, "C1"));
    expect(idOf(before, "C2")).toBe(idOf(after, "C2"));
    expect(idOf(before, "C1")).not.toBe(idOf(before, "C2"));
  });

  it("同一查询从搜索阶段进入章节总结时保持卡片身份", () => {
    const searching = {
      messageType: "deep_search",
      messageTime: "1714041600003",
      resultMap: {
        messageType: "search",
        searchResult: {
          query: ["查询一"],
          docs: [[{ link: "https://example.com", title: "来源一" }]],
        },
      },
    } as unknown as CHAT.Task;
    const summarizing = {
      messageType: "deep_search",
      messageTime: "1714041600003",
      resultMap: {
        messageType: "chapter_summary",
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["查询一"],
            docs: [[{ link: "https://example.com", title: "来源一" }]],
            summary: "第一章总结",
          },
        },
      },
    } as unknown as CHAT.Task;

    const [searchCard] = processTaskForRender(searching, "deep-search-identity-");
    const [chapterCard] = processTaskForRender(summarizing, "deep-search-identity-");

    expect(searchCard.id).toBe(chapterCard.id);
  });

  it("章节分批到达时，尚未总结的查询卡不会被暂时删除", () => {
    const partial = {
      messageType: "deep_search",
      messageTime: "1714041600004",
      resultMap: {
        messageType: "chapter_summary",
        searchResult: {
          query: ["查询一", "查询二"],
          docs: [
            [{ link: "https://example.com/a", title: "来源一" }],
            [{ link: "https://example.com/b", title: "来源二" }],
          ],
        },
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["查询一"],
            docs: [[{ link: "https://example.com/a", title: "来源一" }]],
            summary: "第一章总结",
          },
        },
      },
    } as unknown as CHAT.Task;

    const rendered = processTaskForRender(partial, "deep-search-partial-");
    const queryCard = rendered.find(
      (item) => item.resultMap?.chapterTitle === "查询二"
    );

    expect(rendered).toHaveLength(2);
    expect(queryCard?.resultMap?.messageType).toBe("search");
    expect(queryCard?.id).toContain("query:");
  });

  it("章节逐章到达时，剩余查询卡保持同一 React 身份", () => {
    const makeTask = (withSecondChapter: boolean) => ({
      messageType: "deep_search",
      messageTime: withSecondChapter
        ? "1714041600006"
        : "1714041600005",
      resultMap: {
        messageType: "chapter_summary",
        searchResult: {
          query: ["查询一", "查询二"],
          docs: [
            [{ link: "https://example.com/a", title: "来源一" }],
            [{ link: "https://example.com/b", title: "来源二" }],
          ],
        },
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["查询一"],
            docs: [[{ link: "https://example.com/a", title: "来源一" }]],
            summary: "第一章总结",
          },
          ...(withSecondChapter
            ? {
              C2: {
                chapterId: "C2",
                chapterTitle: "第二章",
                chapterOrder: 2,
                queries: ["查询二"],
                docs: [[{ link: "https://example.com/b", title: "来源二" }]],
                summary: "第二章总结",
              },
            }
            : {}),
        },
      },
    }) as unknown as CHAT.Task;

    const before = processTaskForRender(makeTask(false), "deep-search-incremental-");
    const after = processTaskForRender(makeTask(true), "deep-search-incremental-");
    const beforeSecond = before.find(
      (item) => item.resultMap?.chapterTitle === "查询二"
    );
    const afterSecond = after.find(
      (item) => item.resultMap?.chapterId === "C2"
    );

    expect(beforeSecond?.id).toBe(afterSecond?.id);
  });

  it("章节逐章到达时，卡片顺序和已有身份都不变化", () => {
    const makeTask = (withSecondChapter: boolean) => ({
      messageType: "deep_search",
      messageTime: withSecondChapter
        ? "1714041600008"
        : "1714041600007",
      resultMap: {
        messageType: "chapter_summary",
        searchResult: {
          query: ["查询一", "查询二", "查询三"],
          docs: [
            [{ link: "https://example.com/a", title: "来源一" }],
            [{ link: "https://example.com/b", title: "来源二" }],
            [{ link: "https://example.com/c", title: "来源三" }],
          ],
        },
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["查询一"],
            docs: [[{ link: "https://example.com/a", title: "来源一" }]],
            summary: "第一章总结",
          },
          ...(withSecondChapter
            ? {
              C2: {
                chapterId: "C2",
                chapterTitle: "第二章",
                chapterOrder: 2,
                queries: ["查询二"],
                docs: [[{ link: "https://example.com/b", title: "来源二" }]],
                summary: "第二章总结",
              },
            }
            : {}),
        },
      },
    }) as unknown as CHAT.Task;

    const before = processTaskForRender(makeTask(false), "deep-search-order-");
    const after = processTaskForRender(makeTask(true), "deep-search-order-");

    expect(before.map((item) => item.id)).toEqual(
      after.slice(0, before.length).map((item) => item.id)
    );
    expect(after.map((item) => item.resultMap?.chapterId || item.resultMap?.chapterTitle))
      .toEqual(["C1", "C2", "查询三"]);
  });

  it("目录先到、查询词后补齐时章节卡保持同一身份", () => {
    const catalogOnly = {
      messageType: "deep_search",
      resultMap: {
        messageType: "search",
        searchResult: {
          query: ["查询一"],
          docs: [],
          chapters: [
            {
              chapterId: "C1",
              chapterTitle: "第一章",
              chapterOrder: 1,
              queries: [],
            },
          ],
        },
      },
    } as unknown as CHAT.Task;
    const summarizing = {
      messageType: "deep_search",
      resultMap: {
        messageType: "chapter_summary",
        searchResult: {
          query: ["查询一"],
          docs: [[{ link: "https://example.com/a", title: "来源一" }]],
        },
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["查询一"],
            docs: [[{ link: "https://example.com/a", title: "来源一" }]],
            summary: "第一章总结",
          },
        },
      },
    } as unknown as CHAT.Task;

    const [catalogCard] = processTaskForRender(
      catalogOnly,
      "deep-search-catalog-"
    );
    const [summaryCard] = processTaskForRender(
      summarizing,
      "deep-search-catalog-"
    );

    expect(catalogCard.id).toBe(summaryCard.id);
  });

  it("Deep Research 子卡不继承最终报告的 answer 和章节总结", () => {
    const task = {
      messageType: "deep_search",
      messageTime: "1714041600009",
      resultMap: {
        messageType: "chapter_summary",
        answer: "最终总结",
        chapterSummary: "最终总结",
        chapterTitle: "最终总结",
        searchResult: {
          query: ["查询一", "查询二"],
          docs: [
            [{ link: "https://example.com/a", title: "来源一" }],
            [{ link: "https://example.com/b", title: "来源二" }],
          ],
        },
        chapters: {
          C1: {
            chapterId: "C1",
            chapterTitle: "第一章",
            chapterOrder: 1,
            queries: ["查询一"],
            docs: [[{ link: "https://example.com/a", title: "来源一" }]],
            summary: "第一章总结",
          },
          C2: {
            chapterId: "C2",
            chapterTitle: "第二章",
            chapterOrder: 2,
            queries: ["查询二"],
            docs: [[{ link: "https://example.com/b", title: "来源二" }]],
            summary: "第二章总结",
          },
        },
      },
    } as unknown as CHAT.Task;

    const rendered = processTaskForRender(task, "deep-search-isolated-");

    expect(rendered.map((item) => item.resultMap?.chapterSummary)).toEqual([
      "第一章总结",
      "第二章总结",
    ]);
    expect(rendered.map((item) => item.resultMap?.answer)).toEqual([
      "第一章总结",
      "第二章总结",
    ]);
    expect(rendered.every((item) => item.resultMap?.answer !== "最终总结")).toBe(
      true
    );
  });
});

describe("getTaskRenderSignature 缓存失效契约", () => {
  const base = {
    messageId: "m1",
    messageType: "tool_call",
    messageTime: "1714041600000",
    resultMap: {
      messageType: "tool_call",
      toolName: "workspace_read",
      toolCallId: "c1",
      argumentsText: '{"path":"a.ts"}',
    },
  } as unknown as Parameters<typeof getTaskRenderSignature>[0];

  const sig = (patch: Record<string, unknown>, baseId = "m1") =>
    getTaskRenderSignature({ ...base, ...patch } as never, baseId);

  it("同一任务两次求值稳定", () => {
    expect(getTaskRenderSignature(base, "m1")).toBe(
      getTaskRenderSignature(base, "m1")
    );
  });

  it("baseId 变化即使任务相同也失效", () => {
    expect(getTaskRenderSignature(base, "m1")).not.toBe(
      getTaskRenderSignature(base, "m2")
    );
  });

  it("Deep Search 的实时 messageTime 变化不会单独让渲染缓存失效", () => {
    const deepSearch = {
      messageId: "deep-1",
      messageType: "deep_search",
      messageTime: "1714041600000",
      resultMap: {
        messageType: "search",
        searchResult: {
          query: ["查询一"],
          docs: [[]],
        },
      },
    } as unknown as Parameters<typeof getTaskRenderSignature>[0];

    expect(
      getTaskRenderSignature(
        { ...deepSearch, messageTime: "1714041600001" },
        "deep"
      )
    ).toBe(getTaskRenderSignature(deepSearch, "deep"));
  });

  it.each([
    ["messageId", { messageId: "m2" }],
    ["messageType", { messageType: "tool_result" }],
    ["messageTime", { messageTime: "1714041600001" }],
    ["resultMap.status", { resultMap: { ...base.resultMap, status: "running" } }],
    ["resultMap.summary", { resultMap: { ...base.resultMap, summary: "s" } }],
    ["resultMap.isFinal", { resultMap: { ...base.resultMap, isFinal: true } }],
    [
      "argumentsText 增长",
      { resultMap: { ...base.resultMap, argumentsText: '{"path":"a.ts","x":1}' } },
    ],
    [
      "argumentsText 相同长度但内容不同",
      { resultMap: { ...base.resultMap, argumentsText: '{"path":"b.ts"}' } },
    ],
    ["toolThought", { toolThought: "想一下" }],
    ["plan.stepStatus", { plan: { steps: [], stepStatus: ["completed"] } }],
  ])("展示字段 %s 变化必须使签名失效", (_label, patch) => {
    expect(sig(patch)).not.toBe(getTaskRenderSignature(base, "m1"));
  });

  it("artifactRefs 变化必须使签名失效", () => {
    const withRefs = sig({
      artifactRefs: [{ resourceKey: "k1", previewUrl: "u1" }],
    });
    expect(withRefs).not.toBe(getTaskRenderSignature(base, "m1"));
    // 同一 key 的另一个 URL 也要失效
    expect(
      sig({ artifactRefs: [{ resourceKey: "k1", previewUrl: "u2" }] })
    ).not.toBe(withRefs);
  });
});

describe("签名对大 payload 的规模", () => {
  const base = {
    messageId: "m1",
    messageType: "tool_call",
    messageTime: "1714041600000",
  } as unknown as Parameters<typeof getTaskRenderSignature>[0];

  const sigWith = (argumentsText: string) =>
    getTaskRenderSignature(
      {
        ...base,
        resultMap: { messageType: "tool_call", argumentsText },
      } as never,
      "m1"
    );

  it("签名长度不随入参体积线性增长", () => {
    const small = sigWith('{"a":1}');
    const huge = sigWith(`{"a":"${"x".repeat(200_000)}"}`);
    // 全文进签名时 huge 会比 small 长约 20 万字符；哈希方案下两者同量级
    expect(huge.length).toBeLessThan(small.length + 200);
  });

  it("同长度不同内容仍能区分", () => {
    expect(sigWith('{"path":"a.ts"}')).not.toBe(sigWith('{"path":"b.ts"}'));
  });

  it("内容相同则签名相同（缓存可命中）", () => {
    expect(sigWith('{"a":1}')).toBe(sigWith('{"a":1}'));
  });
});

describe("DeepSearch React 身份", () => {
  it("同一次工具调用的多张阶段卡不会共用父 toolCallId", () => {
    const task = {
      messageType: "deep_search",
      messageId: "deep-msg",
      messageTime: "1714041600000",
      resultMap: {
        messageType: "search",
        toolCallId: "call-deep-1",
        searchResult: {
          query: ["查询一", "查询二"],
          docs: [[], []],
        },
      },
    } as unknown as CHAT.Task;

    const cards = canonicalizeDeepSearchTask(
      task,
      "deep_search:call-deep-1:research",
    );
    const keys = cards.map((card) => resolveToolKey(card.task));

    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(2);
  });
});
