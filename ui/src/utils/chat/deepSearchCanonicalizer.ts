import { hashContent } from "@/lib/hashContent";
import type { AgentTimelineItem } from "@/types/timeline";
import { recordTimelineDiagnostic } from "@/utils/timeline/timelineDiagnostics";

type RenderableTask = MESSAGE.Task | CHAT.Task;

type RenderSearchResult = {
  query: string[];
  docs: MESSAGE.Doc[];
};

type DeepSearchStageName =
  | "extend"
  | "search"
  | "chapter_summary"
  | "report";

export type DeepSearchCardSnapshot = {
  itemId: string;
  task: CHAT.Task;
  stage: DeepSearchStageName;
  order: number;
};

type SearchResultWithChapters = MESSAGE.SearchResult & {
  chapters?:
    | Record<string, MESSAGE.DeepSearchChapterState>
    | MESSAGE.DeepSearchChapterState[];
};

type DeepSearchChapterEntry = {
  key: string;
  chapter: MESSAGE.DeepSearchChapterState;
};

type DeepSearchRawStage = MESSAGE.ResultMap & {
  queries?: unknown;
  results?: unknown;
  stages?: unknown[];
};

const DEEP_SEARCH_STAGES = new Set<DeepSearchStageName>([
  "extend",
  "search",
  "chapter_summary",
  "report",
]);

function stableToken(identity: string): string {
  return `${identity.length}-${hashContent(identity)}`;
}

function isDeepSearchStage(value: unknown): value is DeepSearchStageName {
  return typeof value === "string"
    && DEEP_SEARCH_STAGES.has(value as DeepSearchStageName);
}

export function isDeepSearchStageMap(resultMap?: MESSAGE.ResultMap | null): boolean {
  if (!resultMap) {
    return false;
  }
  return Boolean(
    resultMap.searchResult
      || resultMap.chapters
      || resultMap.chapterSummary
      || resultMap.chapterId
      || isDeepSearchStage(resultMap.messageType),
  );
}

/**
 * Deep Research 的实时帧与历史帧包装层数不同。这里只做“找到业务阶段”
 * 这一件事，阶段推断本身不再散落在各个组件里。
 */
export function resolveDeepSearchInnerMap(
  task: RenderableTask,
): MESSAGE.ResultMap | undefined {
  const nested = task.resultMap as MESSAGE.ResultMap | undefined;
  if (isDeepSearchStageMap(nested) && nested?.messageType !== "deep_search") {
    return nested;
  }
  if (isDeepSearchStageMap(nested?.resultMap)) {
    return nested?.resultMap;
  }
  if (nested?.searchResult || nested?.chapters || nested?.chapterSummary) {
    return nested;
  }
  const flat = task as MESSAGE.ResultMap & RenderableTask;
  if (flat.searchResult) {
    return flat as unknown as MESSAGE.ResultMap;
  }
  return nested?.resultMap || nested;
}

export function inferDeepSearchStage(
  inner?: MESSAGE.ResultMap,
): DeepSearchStageName {
  if (isDeepSearchStage(inner?.messageType)) {
    return inner.messageType;
  }
  if (inner?.chapterSummary || inner?.chapterId) {
    return "chapter_summary";
  }
  if (String(inner?.answer || "").trim() && !inner?.searchResult?.query?.length) {
    return "report";
  }
  if (inner?.searchFinish === false) {
    return "extend";
  }
  return "search";
}

/**
 * 历史 DeepSearch 输出把 query 列表放在 stage.queries，而实时事件把它
 * 放在 searchResult.query。canonicalizer 在入口统一成后者，后续拆卡只认
 * 一种形态。
 */
function normalizeDeepSearchInner(
  inner?: MESSAGE.ResultMap,
): MESSAGE.ResultMap | undefined {
  if (!inner) {
    return inner;
  }
  const raw = inner as MESSAGE.ResultMap & {
    queries?: unknown;
    results?: unknown;
  };
  const queries = Array.isArray(raw.queries)
    ? raw.queries.map((query) => String(query || "").trim()).filter(Boolean)
    : [];
  const results = Array.isArray(raw.results) ? raw.results : [];
  if (!queries.length && !results.length) {
    return inner;
  }

  const existing = inner.searchResult;
  const docsByQuery = new Map<string, MESSAGE.Doc[]>();
  const addDocs = (query: string, docs: unknown) => {
    if (!query || !Array.isArray(docs)) {
      return;
    }
    const previous = docsByQuery.get(query) || [];
    docsByQuery.set(query, [
      ...previous,
      ...docs.filter((doc): doc is MESSAGE.Doc =>
        Boolean(doc) && typeof doc === "object"
      ),
    ]);
  };
  (existing?.query || []).forEach((query, index) => {
    addDocs(String(query || ""), existing?.docs?.[index]);
  });
  queries.forEach((query) => addDocs(query, []));
  results.forEach((result) => {
    const record = result as Record<string, unknown>;
    addDocs(String(record.query || ""), record.docs);
  });

  const normalizedQueries = [...new Set([
    ...(existing?.query || []).map((query) => String(query || "")),
    ...queries,
    ...results.map((result) => String(
      (result as Record<string, unknown>).query || "",
    )),
  ].map((query) => query.trim()).filter(Boolean))];
  return {
    ...inner,
    searchResult: {
      ...(existing || {}),
      query: normalizedQueries,
      docs: normalizedQueries.map((query) => docsByQuery.get(query) || []),
    },
  } as MESSAGE.ResultMap;
}

/**
 * 历史 DeepSearch 输出使用 stages[]，而实时链路逐帧发送。把历史 stages
 * 展开为逐阶段快照，后面的拆卡逻辑才能对两种来源使用同一套规则。
 */
export function expandDeepSearchStages(
  inner?: MESSAGE.ResultMap,
): MESSAGE.ResultMap[] {
  if (!inner) {
    return [];
  }
  const raw = inner as DeepSearchRawStage;
  const stages = Array.isArray(raw.stages) ? raw.stages : [];
  if (!stages.length) {
    return [inner];
  }
  const chapterState = new Map<string, MESSAGE.DeepSearchChapterState>();
  const expanded: MESSAGE.ResultMap[] = [];
  for (const value of stages) {
    if (!value || typeof value !== "object") {
      continue;
    }
    const stage = value as Record<string, unknown>;
    const stageType = String(stage.stage || stage.messageType || "");
    if (!isDeepSearchStage(stageType)) {
      continue;
    }
    if (stageType === "chapter_summary") {
      const title = String(stage.chapterTitle || "").trim();
      const queries = Array.isArray(stage.queries)
        ? stage.queries.map((query) => String(query || "")).filter(Boolean)
        : [];
      const key = String(stage.chapterId || title || `chapter-${chapterState.size}`);
      // 章节总结阶段的历史载荷正文通常就在 answer；空正文也必须保留章节卡，
      // 由 chapterStreaming 表达“已到达但仍在生成”。
      const summary = String(stage.answer || stage.chapterSummary || "");
      chapterState.set(key, {
        chapterId: String(stage.chapterId || key),
        chapterTitle: title || queries[0] || key,
        chapterOrder: chapterState.size + 1,
        queries,
        docs: [],
        summary,
        streaming: false,
      });
      expanded.push({
        ...inner,
        messageType: stageType,
        isFinal: Boolean(stage.isFinal),
        searchFinish: Boolean(stage.searchFinish),
        answer: summary,
        chapterId: String(stage.chapterId || key),
        chapterTitle: title || queries[0] || key,
        chapterSummary: summary,
        chapterStreaming: true,
        searchResult: {
          query: queries,
          docs: [],
        },
        chapters: Object.fromEntries(chapterState.entries()),
      } as MESSAGE.ResultMap);
      continue;
    }
    const results = Array.isArray(stage.results) ? stage.results : [];
    const searchResult = {
      query: [
        ...(Array.isArray(stage.queries)
          ? stage.queries.map((query) => String(query || "")).filter(Boolean)
          : []),
        ...results.map((result) => String(
          (result as Record<string, unknown>)?.query || "",
        )).filter(Boolean),
      ],
      docs: results.map((result) => {
        const docs = (result as Record<string, unknown>)?.docs;
        return Array.isArray(docs) ? docs as MESSAGE.Doc[] : [];
      }),
    };
    expanded.push({
      ...inner,
      messageType: stageType,
      isFinal: Boolean(stage.isFinal),
      searchFinish: Boolean(stage.searchFinish),
      answer: String(stage.answer || ""),
      chapterId: String(stage.chapterId || ""),
      chapterTitle: String(stage.chapterTitle || ""),
      chapterSummary: String(stage.answer || stage.chapterSummary || ""),
      chapterStreaming: false,
      searchResult,
      chapters: chapterState.size
        ? Object.fromEntries(chapterState.entries())
        : undefined,
    } as MESSAGE.ResultMap);
  }
  return expanded.length ? expanded : [inner];
}

function readChapterEntries(
  value:
    | Record<string, MESSAGE.DeepSearchChapterState>
    | MESSAGE.DeepSearchChapterState[]
    | undefined
    | null,
): DeepSearchChapterEntry[] {
  if (Array.isArray(value)) {
    return value.reduce<DeepSearchChapterEntry[]>((entries, chapter, index) => {
      if (chapter) {
        entries.push({
          key: chapter.chapterId || chapter.chapterTitle || `chapter-${index}`,
          chapter,
        });
      }
      return entries;
    }, []);
  }
  if (!value) {
    return [];
  }
  return Object.entries(value).reduce<DeepSearchChapterEntry[]>(
    (entries, [key, chapter]) => {
      if (chapter) {
        entries.push({ key: chapter.chapterId || key, chapter });
      }
      return entries;
    },
    [],
  );
}

export function collectDeepSearchChapters(
  inner?: MESSAGE.ResultMap,
): DeepSearchChapterEntry[] {
  const searchResult = inner?.searchResult as SearchResultWithChapters | undefined;
  const chapters = new Map<string, MESSAGE.DeepSearchChapterState>();
  for (const { key, chapter } of readChapterEntries(searchResult?.chapters)) {
    chapters.set(key, chapter);
  }
  for (const { key, chapter } of readChapterEntries(inner?.chapters)) {
    const previous = chapters.get(key);
    chapters.set(key, {
      ...previous,
      ...chapter,
      chapterId: chapter.chapterId || previous?.chapterId || key,
      chapterTitle: chapter.chapterTitle || previous?.chapterTitle,
      chapterContent: chapter.chapterContent || previous?.chapterContent,
      chapterOrder: chapter.chapterOrder ?? previous?.chapterOrder,
      queries: chapter.queries?.length ? chapter.queries : previous?.queries,
      docs: chapter.docs?.length ? chapter.docs : previous?.docs,
      summary: chapter.summary || previous?.summary,
      streaming: chapter.streaming ?? previous?.streaming,
    });
  }
  return [...chapters.entries()]
    .map(([key, chapter]) => ({ key, chapter }))
    .sort((left, right) => {
      const leftOrder = left.chapter.chapterOrder ?? Number.MAX_SAFE_INTEGER;
      const rightOrder = right.chapter.chapterOrder ?? Number.MAX_SAFE_INTEGER;
      return leftOrder - rightOrder;
    });
}

function findChapterEntryForQuery(
  chapterEntries: DeepSearchChapterEntry[],
  query: string,
): DeepSearchChapterEntry | undefined {
  const normalizedQuery = String(query || "").trim();
  if (!normalizedQuery) {
    return undefined;
  }
  return chapterEntries.find(({ chapter }) =>
    String(chapter.chapterTitle || "").trim() === normalizedQuery
    || (chapter.queries || []).some(
      (value) => String(value || "").trim() === normalizedQuery,
    )
  );
}

function findChapterForQuery(
  chapters: Record<string, MESSAGE.DeepSearchChapterState> | undefined | null,
  query: string,
): MESSAGE.DeepSearchChapterState | undefined {
  if (!chapters || !query) {
    return undefined;
  }
  return Object.values(chapters).find((chapter) =>
    chapter?.chapterTitle === query
    || (Array.isArray(chapter?.queries) && chapter.queries.includes(query))
  );
}

function resolveChapterDocs(
  chapter: MESSAGE.DeepSearchChapterState,
  chapterIndex: number,
  chapterCount: number,
  searchResult?: MESSAGE.SearchResult,
): MESSAGE.Doc[] {
  if (chapter.docs?.length) {
    const docs = chapter.docs.flatMap((bucket) =>
      Array.isArray(bucket) ? bucket : []
    );
    if (docs.length) {
      return docs;
    }
  }
  const firstQuery = chapter.queries?.find(Boolean);
  const queryIndex = firstQuery
    ? (searchResult?.query || []).indexOf(firstQuery)
    : -1;
  if (queryIndex < 0) {
    const populatedBuckets = (searchResult?.docs || []).filter(
      (bucket) => Array.isArray(bucket) && bucket.length > 0,
    );
    if (populatedBuckets.length !== chapterCount) {
      return [];
    }
    return populatedBuckets[chapterIndex] || [];
  }
  const bucket = searchResult?.docs?.[queryIndex];
  return Array.isArray(bucket) ? bucket : [];
}

function pickCardResultMap(resultMap: MESSAGE.ResultMap): MESSAGE.ResultMap {
  const copy = { ...resultMap } as MESSAGE.ResultMap;
  delete copy.answer;
  delete copy.chapterContent;
  delete copy.chapterId;
  delete copy.chapterOrder;
  delete copy.chapterStreaming;
  delete copy.chapterSummary;
  delete copy.chapterTitle;
  delete copy.chapters;
  delete copy.messageType;
  delete copy.searchFinish;
  delete copy.searchResult;
  return copy;
}

function createCardTask(
  task: RenderableTask,
  itemId: string,
  searchResult?: RenderSearchResult,
): CHAT.Task {
  const next = {
    ...task,
    id: itemId,
    __timelineRendered: true,
    resultMap: task.resultMap
      ? pickCardResultMap(task.resultMap as MESSAGE.ResultMap)
      : task.resultMap,
    plan: task.plan
      ? {
        ...task.plan,
        notes: [...(task.plan.notes || [])],
        stages: [...(task.plan.stages || [])],
        stepStatus: [...(task.plan.stepStatus || [])],
        steps: [...(task.plan.steps || [])],
      }
      : task.plan,
  } as unknown as CHAT.Task;
  if (searchResult && next.resultMap) {
    next.resultMap.searchResult =
      searchResult as CHAT.Task["resultMap"]["searchResult"];
  }
  return next;
}

function queryIdentity(
  baseId: string,
  query: string,
  occurrence: number,
  primary = false,
): string {
  const identity = String(query || "").trim();
  // 首个真实查询复用占位卡身份，避免 extend 占位卡升级成查询卡时卸载重挂。
  if (primary && occurrence === 0) {
    return baseId;
  }
  return identity
    ? `${baseId}:query:${stableToken(identity)}:${occurrence}`
    : `${baseId}:query:unknown:${occurrence}`;
}

function chapterIdentity(
  baseId: string,
  key: string,
  chapter: MESSAGE.DeepSearchChapterState,
  anchorQuery?: string,
  primary = false,
): string {
  const query = String(
    anchorQuery || (chapter.queries || []).find(Boolean) || "",
  ).trim();
  if (query) {
    return queryIdentity(baseId, query, 0, primary);
  }
  if (primary) {
    return baseId;
  }
  const identity =
    String(chapter.chapterId || "").trim()
    || String(key || "").trim()
    || String(chapter.chapterTitle || "").trim();
  return `${baseId}:chapter:${stableToken(identity || "unknown")}`;
}

function resolveCardStage(
  hasSummary: boolean,
  docsCount: number,
): "chapter_summary" | "search" | "extend" {
  if (hasSummary) {
    return "chapter_summary";
  }
  return docsCount > 0 ? "search" : "extend";
}

function buildQueryCard(
  task: RenderableTask,
  inner: MESSAGE.ResultMap | undefined,
  baseId: string,
  query: string,
  index: number,
  occurrence: number,
  order: number,
): DeepSearchCardSnapshot {
  const chapter = findChapterForQuery(inner?.chapters, query);
  const rawDocs = inner?.searchResult?.docs?.[index] || chapter?.docs?.[0];
  const docs = Array.isArray(rawDocs) ? rawDocs : rawDocs ? [rawDocs] : [];
  const searchResult = { query: [query], docs };
  const itemId = queryIdentity(baseId, query, occurrence, order === 0);
  const renderTask = createCardTask(task, itemId, searchResult);
  if (!renderTask.resultMap) {
    renderTask.resultMap = {} as CHAT.Task["resultMap"];
  }
  const stage = resolveCardStage(Boolean(chapter?.summary), docs.length);
  renderTask.resultMap = {
    ...renderTask.resultMap,
    messageType: stage,
    searchResult: searchResult as CHAT.Task["resultMap"]["searchResult"],
    chapterId: chapter?.chapterId,
    chapterTitle: chapter?.chapterTitle || query,
    chapterOrder: chapter?.chapterOrder ?? index + 1,
    chapterSummary: chapter?.summary || "",
    chapterStreaming: Boolean(chapter?.streaming),
    answer: chapter?.summary || "",
    chapters: inner?.chapters,
  } as unknown as CHAT.Task["resultMap"];
  return { itemId, task: renderTask, stage, order };
}

function buildChapterCard(
  task: RenderableTask,
  inner: MESSAGE.ResultMap | undefined,
  baseId: string,
  entry: DeepSearchChapterEntry,
  index: number,
  chapterCount: number,
  order: number,
  anchorQuery?: string,
  primary = false,
): DeepSearchCardSnapshot {
  const { key, chapter } = entry;
  const chapterTitle = String(chapter.chapterTitle || "").trim();
  const chapterQueries = (chapter.queries || []).filter(Boolean);
  const query = chapterTitle || anchorQuery || chapterQueries[0] || key;
  const docs = resolveChapterDocs(chapter, index, chapterCount, inner?.searchResult);
  const searchResult = {
    query: chapterQueries.length ? chapterQueries : [query],
    docs,
  };
  const itemId = chapterIdentity(
    baseId,
    key,
    chapter,
    anchorQuery,
    primary,
  );
  const renderTask = createCardTask(task, itemId, searchResult);
  if (!renderTask.resultMap) {
    renderTask.resultMap = {} as CHAT.Task["resultMap"];
  }
  const stage = resolveCardStage(Boolean(chapter.summary), docs.length);
  renderTask.resultMap = {
    ...renderTask.resultMap,
    messageType: stage,
    searchResult: searchResult as CHAT.Task["resultMap"]["searchResult"],
    chapterId: chapter.chapterId || key,
    chapterTitle: chapterTitle || query,
    chapterOrder: chapter.chapterOrder ?? index + 1,
    chapterSummary: chapter.summary || "",
    chapterStreaming: Boolean(chapter.streaming),
    answer: chapter.summary || "",
    chapters: inner?.chapters,
  } as unknown as CHAT.Task["resultMap"];
  return { itemId, task: renderTask, stage, order };
}

/**
 * 把一条 Deep Research 工具事实规范化为稳定的展示卡。
 *
 * 实时 SSE 与历史 replay 都先经过这里，组件不再自己按 query/chapter 拆卡。
 */
export function canonicalizeDeepSearchTask(
  task: RenderableTask,
  baseId: string,
): DeepSearchCardSnapshot[] {
  const inner = normalizeDeepSearchInner(resolveDeepSearchInnerMap(task));
  const rawStage = String(inner?.messageType || "").trim();
  if (
    rawStage
    && rawStage !== "deep_search"
    && !isDeepSearchStage(rawStage)
    && isDeepSearchStageMap(inner)
  ) {
    recordTimelineDiagnostic("unknown_stage", {
      itemId: baseId,
      detail: `rawStage=${rawStage}`,
    });
  }
  const messageType = inferDeepSearchStage(inner);
  if (messageType === "report") {
    const reportTask = createCardTask(task, baseId);
    if (reportTask.resultMap && inner && reportTask.resultMap !== inner) {
      reportTask.resultMap = {
        ...reportTask.resultMap,
        ...inner,
        messageType: "report",
      } as unknown as CHAT.Task["resultMap"];
    }
    return [{
      itemId: baseId,
      task: reportTask,
      stage: "report",
      order: 0,
    }];
  }

  const chapterEntries = collectDeepSearchChapters(inner);
  const primaryChapterKey = chapterEntries
    .map((entry) => entry.key)
    .sort((left, right) => left.localeCompare(right))[0];
  const searchQueries = (inner?.searchResult?.query || [])
    .map((query) => String(query || "").trim())
    .filter(Boolean);

  if (chapterEntries.length) {
    const chaptersAreOnlySource =
      messageType !== "chapter_summary"
      && !searchQueries.some((query) =>
        Boolean(findChapterEntryForQuery(chapterEntries, query))
      );
    if (chaptersAreOnlySource) {
      // 章节可能因 chapterOrder 变化重排；baseId 只能绑定稳定的章节 key。
      return chapterEntries.map((entry, index) =>
        buildChapterCard(
          task,
          inner,
          baseId,
          entry,
          index,
          chapterEntries.length,
          index,
          searchQueries[index],
          entry.key === primaryChapterKey,
        )
      );
    }

    const chapterIndexByKey = new Map(
      chapterEntries.map((entry, index) => [entry.key, index]),
    );
    const emitted = new Set<string>();
    const cards: DeepSearchCardSnapshot[] = [];
    const occurrences = new Map<string, number>();
    let baseIdentityUsed = false;
    for (const [index, query] of searchQueries.entries()) {
      const occurrence = occurrences.get(query) || 0;
      occurrences.set(query, occurrence + 1);
      const chapterEntry = findChapterEntryForQuery(chapterEntries, query);
      if (chapterEntry) {
        if (emitted.has(chapterEntry.key)) {
          continue;
        }
        emitted.add(chapterEntry.key);
        cards.push(
          buildChapterCard(
            task,
            inner,
            baseId,
            chapterEntry,
            chapterIndexByKey.get(chapterEntry.key) ?? 0,
            chapterEntries.length,
            index,
            query,
            index === 0,
          ),
        );
        if (index === 0) {
          baseIdentityUsed = true;
        }
        continue;
      }
      cards.push(
        buildQueryCard(task, inner, baseId, query, index, occurrence, index),
      );
      if (index === 0) {
        baseIdentityUsed = true;
      }
    }
    for (const [index, entry] of chapterEntries.entries()) {
      if (emitted.has(entry.key)) {
        continue;
      }
      const primary = entry.key === primaryChapterKey && !baseIdentityUsed;
      cards.push(
        buildChapterCard(
          task,
          inner,
          baseId,
          entry,
          index,
          chapterEntries.length,
          searchQueries.length + index,
          undefined,
          primary,
        ),
      );
      if (primary) {
        baseIdentityUsed = true;
      }
    }
    return cards;
  }

  if (!searchQueries.length) {
    const fallback = createCardTask(task, baseId);
    if (fallback.resultMap && inner && fallback.resultMap !== inner) {
      fallback.resultMap = {
        ...fallback.resultMap,
        ...inner,
        messageType: messageType === "extend" ? "extend" : "search",
      } as unknown as CHAT.Task["resultMap"];
    }
    return [{
      itemId: baseId,
      task: fallback,
      stage: messageType === "extend" ? "extend" : "search",
      order: 0,
    }];
  }

  const occurrences = new Map<string, number>();
  return searchQueries.map((query, index) => {
    const occurrence = occurrences.get(query) || 0;
    occurrences.set(query, occurrence + 1);
    return buildQueryCard(
      task,
      inner,
      baseId,
      query,
      index,
      occurrence,
      index,
    );
  });
}

/**
 * 历史 replay 的 stages[] 统一展开为多张稳定卡。
 */
export function canonicalizeDeepSearchStages(
  task: RenderableTask,
  baseId: string,
): DeepSearchCardSnapshot[] {
  const inner = resolveDeepSearchInnerMap(task);
  const stages = expandDeepSearchStages(inner);
  if (stages.length <= 1) {
    return canonicalizeDeepSearchTask(task, baseId);
  }
  const cards: DeepSearchCardSnapshot[] = [];
  const cardIndexById = new Map<string, number>();
  for (const stage of stages) {
    const stageMessageType = String(stage.messageType || "");
    const stageTask = {
      ...task,
      resultMap: {
        ...(task.resultMap || {}),
        messageType: "deep_search",
        resultMap: stage,
      },
    } as unknown as RenderableTask;
    // 实时链路 report 使用 `:report`，其余阶段统一使用 research 身份。
    // 历史 stages[] 必须复用同一条身份规则，否则刷新后会变成一批新卡。
    const stageBaseId =
      stageMessageType === "report"
        ? baseId.replace(/:research$/, ":report")
        : baseId.replace(/:report$/, ":research");
    const stageCards = canonicalizeDeepSearchTask(
      stageTask,
      stageBaseId,
    );
    for (const card of stageCards) {
      if (
        stageMessageType === "chapter_summary"
        && card.stage !== "chapter_summary"
      ) {
        // 历史章节阶段即使尚未携带总结正文，也必须作为章节卡到达，
        // 不能被降级成查询卡后丢掉。
        card.stage = "chapter_summary";
        card.task.resultMap = {
          ...card.task.resultMap,
          messageType: "chapter_summary",
        } as CHAT.Task["resultMap"];
      }
      const existingIndex = cardIndexById.get(card.itemId);
      if (existingIndex === undefined) {
        cardIndexById.set(card.itemId, cards.length);
        cards.push(card);
      } else {
        // 同一 query/chapter 的阶段推进是 upsert，不是新卡。
        cards[existingIndex] = card;
      }
    }
  }
  return cards;
}

/**
 * 影子时间线只做对账，不参与 UI 读取。
 *
 * 它把 Deep Research 的多阶段事实映射为同一个工具 item 下的稳定子项身份，
 * 供测试和开发环境比较“旧渲染是否发生 key churn”。
 */
export function buildDeepSearchShadowItems(
  task: RenderableTask,
  baseId: string,
  input: {
    runId?: string;
    sessionId?: string;
    seq?: number;
  } = {},
): AgentTimelineItem[] {
  return canonicalizeDeepSearchTask(task, baseId).map((card, index) => ({
    protocolVersion: "timeline.v1",
    operation: "upsert",
    eventType: "item.upserted",
    eventId: `${input.runId || "run"}:${baseId}:${index}`,
    runId: input.runId,
    sessionId: input.sessionId,
    seq: Number(input.seq || 0) + index,
    itemId: card.itemId,
    parentItemId: `tool:${resolveToolCallId(task)}`,
    kind: "TOOL_CALL",
    phase: "INTERMEDIATE",
    itemStatus: "RUNNING",
    orderKey: index,
    payload: {
      eventData: {
        messageType: "task",
        messageId: task.messageId,
        taskId: task.taskId,
        resultMap: card.task.resultMap,
      } as unknown as MESSAGE.EventData,
    },
  }));
}

function resolveToolCallId(task: RenderableTask): string {
  const resultMap = task.resultMap as Record<string, unknown> | undefined;
  const nested = resultMap?.resultMap as Record<string, unknown> | undefined;
  return String(
    resultMap?.toolCallId
      || nested?.toolCallId
      || task.messageId
      || "unknown",
  );
}
