import { resolveToolCallTargetName } from "./toolCalls";
import { hashContent } from "@/lib/hashContent";
import {
  canonicalizeDeepSearchStages,
  isDeepSearchStageMap,
} from "./deepSearchCanonicalizer";

type RenderSearchResult = {
  query: string[];
  docs: MESSAGE.Doc[];
};

type RenderableTask = MESSAGE.Task | CHAT.Task;

type TaskRenderCacheEntry = {
  signature: string;
  items: CHAT.Task[];
};

const taskRenderCache = new WeakMap<object, TaskRenderCacheEntry>();

function cloneSearchResultSnapshot(searchResult?: MESSAGE.SearchResult) {
  if (!searchResult) {
    return searchResult;
  }

  // SSE 合并会原地更新 searchResult；渲染前复制数组，避免缓存中的旧任务被后续事件悄悄改写。
  return {
    ...searchResult,
    query: [...(searchResult.query || [])],
    docs: (searchResult.docs || []).map((item) =>
      Array.isArray(item) ? [...item] : item
    ),
  };
}

function cloneResultMapSnapshot(
  resultMap?: MESSAGE.ResultMap
): MESSAGE.ResultMap {
  if (!resultMap) {
    return {} as MESSAGE.ResultMap;
  }

  // 只复制渲染链会读取的可变数组，保留其它字段引用以控制复制成本。
  const chapters = resultMap.chapters
    ? Object.fromEntries(
      Object.entries(resultMap.chapters).map(([key, chapter]) => [
        key,
        chapter
          ? {
            ...chapter,
            queries: [...(chapter.queries || [])],
            docs: (chapter.docs || []).map((bucket) =>
              Array.isArray(bucket) ? [...bucket] : []
            ),
          }
          : chapter,
      ])
    )
    : resultMap.chapters;

  return {
    ...resultMap,
    searchResult: cloneSearchResultSnapshot(resultMap.searchResult),
    chapters,
    fileInfo: [...(resultMap.fileInfo || [])],
    fileList: [...(resultMap.fileList || [])],
    refList: [...(resultMap.refList || [])],
    steps: [...(resultMap.steps || [])],
  };
}

export function clonePlanForRender(plan?: MESSAGE.Plan) {
  if (!plan) {
    return plan;
  }

  return {
    ...plan,
    notes: [...(plan.notes || [])],
    stages: [...(plan.stages || [])],
    stepStatus: [...(plan.stepStatus || [])],
    steps: [...(plan.steps || [])],
  };
}

/**
 * 任务事实的轻量版本签名：结构 + 每张卡的完成度。
 *
 * 用途是给「要不要重建 chat.tasks」提供一个**能反映原地修改**的判据。
 * `combineData` 是 `multiAgent.tasks.push(...)` 原地改写，引用永远不变，
 * 拿引用比对会把同一轮里第二个及以后的工具卡全部跳过。
 *
 * 只取分组数、每组卡片数、以及每张卡的 `messageType` / `isFinal` /
 * `resultMap.status` / 入参与出参的长度 —— 这几项覆盖了「卡片新增」与
 * 「同一张卡内容推进」两类变化，又不至于每帧对全文做哈希。
 *
 * 子 Agent 的 `subagent_start` / `subagent_progress` 只原地写 childSessionId、
 * 相位和进度字段，不碰 status / result；这些字段必须进签名，否则派生时间线
 * 会停在首帧，详情面板拿不到 childSessionId 只能退回合成视图。
 */
export function buildTaskFactsSignature(
  multiAgent?: MESSAGE.MultiAgent
): string {
  const groups = multiAgent?.tasks;
  if (!groups?.length) {
    return "";
  }
  const parts: string[] = [];
  for (const group of groups) {
    const cards: string[] = [];
    for (const task of group || []) {
      const resultMap = (task?.resultMap || {}) as Record<string, unknown>;
      const nested = (resultMap.resultMap || {}) as Record<string, unknown>;
      const progressLines = Array.isArray(resultMap.subAgentProgressLines)
        ? resultMap.subAgentProgressLines
        : [];
      cards.push(
        [
          task?.messageType || "",
          task?.isFinal ? 1 : 0,
          String(resultMap.status || ""),
          String(resultMap.result || "").length,
          JSON.stringify(resultMap.args ?? "").length,
          String(resultMap.childSessionId || nested.childSessionId || ""),
          String(resultMap.subAgentId || ""),
          String(resultMap.subAgentType || ""),
          String(resultMap.subAgentPhase || ""),
          String(resultMap.subAgentDescription || "").length,
          String(resultMap.subAgentSummary || "").length,
          progressLines.length,
          String(progressLines[progressLines.length - 1] || ""),
          String(resultMap.subAgentLiveText || "").length,
          String(resultMap.subAgentElapsedMs ?? ""),
          String(resultMap.childRequestId || nested.childRequestId || ""),
          getDeepSearchFactsSignature(resultMap),
        ].join(":")
      );
    }
    parts.push(cards.join(","));
  }
  return parts.join("|");
}

export function cloneTaskSnapshot(task: MESSAGE.Task): MESSAGE.Task {
  return {
    ...task,
    plan: clonePlanForRender(task.plan),
    resultMap: cloneResultMapSnapshot(task.resultMap),
    toolResult: task.toolResult
      ? {
        ...task.toolResult,
        toolParam: task.toolResult.toolParam
          ? { ...task.toolResult.toolParam }
          : task.toolResult.toolParam,
      }
      : task.toolResult,
  };
}

function getChaptersSignature(resultMap?: MESSAGE.ResultMap): string {
  const chapters = resultMap?.chapters;
  if (!chapters) {
    return "";
  }
  return Object.keys(chapters)
    .sort()
    .map((key) => {
      const chapter = chapters[key];
      return [
        key,
        hashTextForSignature(String(chapter?.summary || "")),
        chapter?.docs?.length || 0,
        chapter?.streaming ? 1 : 0,
        hashTextForSignature((chapter?.queries || []).join("\n")),
      ].join(":");
    })
    .join(",");
}

/**
 * Deep Research 的阶段内容必须进入任务事实签名，否则同一任务对象原地更新时，
 * `buildConversationTaskData` 会误判任务树没有变化，直到最终 isFinal 才刷新。
 */
function getDeepSearchFactsSignature(
  resultMap: Record<string, unknown>
): string {
  const nested = (resultMap.resultMap || {}) as Record<string, unknown>;
  const stageMap = isDeepSearchStageMap(nested as MESSAGE.ResultMap)
    ? nested
    : isDeepSearchStageMap(resultMap as MESSAGE.ResultMap)
      ? resultMap
      : null;
  if (!stageMap) {
    return "";
  }
  const searchResult = (stageMap.searchResult || {}) as Record<string, unknown>;
  const queries = Array.isArray(searchResult.query)
    ? searchResult.query.map((value) => String(value || ""))
    : [];
  const docs = Array.isArray(searchResult.docs) ? searchResult.docs : [];
  const docsSignature = docs
    .map((bucket) => {
      if (!Array.isArray(bucket)) {
        return "0";
      }
      const sourceIdentity = bucket
        .map((doc) => {
          const record = (doc || {}) as Record<string, unknown>;
          return `${String(record.link || "")}|${String(record.title || "")}`;
        })
        .join("\n");
      return `${bucket.length}:${hashTextForSignature(sourceIdentity)}`;
    })
    .join(",");

  return [
    String(stageMap.messageType || ""),
    stageMap.isFinal ? 1 : 0,
    stageMap.searchFinish ? 1 : 0,
    hashTextForSignature(queries.join("\n")),
    docsSignature,
    getChaptersSignature(stageMap as MESSAGE.ResultMap),
    String(stageMap.chapterId || ""),
    hashTextForSignature(String(stageMap.chapterTitle || "")),
    hashTextForSignature(String(stageMap.chapterSummary || "")),
    stageMap.chapterStreaming ? 1 : 0,
    hashTextForSignature(String(stageMap.answer || "")),
  ].join(":");
}

/**
 * 流式入参进签名用的摘要：长度 + 内容哈希。
 *
 * 只放长度会漏「同长度不同内容」的原地更新（'{"path":"a.ts"}' →
 * '{"path":"b.ts"}'），WeakMap 返回旧结果、卡片显示过期入参。
 * 但直接放全文也不行：这个签名每帧对每个 task 都要重算，一次几百 KB 的
 * 工具入参会把字符串拼接和比较都放大到不可忽略的量级。
 *
 * 哈希是 O(n) 但只产出一个短串，拼接与比较都是常数级。
 *
 * **残留风险**：djb2 是 32 位，长内容下仍有非零碰撞概率 —— 一旦碰撞，
 * WeakMap 会返回上一次的渲染结果，卡片短暂显示过期入参，直到长度或哈希
 * 再次变化。这个窗口很小（碰撞需要内容恰好落到同一个 32 位值），
 * 且后果是显示陈旧而非崩溃，所以没有换成更强的哈希；真要根除得把内容
 * 本身进签名，代价是每帧的 O(n) 字符串比较。
 */
function hashTextForSignature(text: string): string {
  return `${text.length}:${hashContent(text)}`;
}

function getArtifactSignature(task: RenderableTask): string {
  const resultMap = task.resultMap || {};
  const files: unknown[] = [
    ...(task.fileList || []),
    ...(resultMap.fileList || []),
    ...(task.artifactRefs || []),
    ...(resultMap.artifactRefs || []),
  ];
  return files
    .map((value) => {
      const file = (value || {}) as Record<string, unknown>;
      return [
        file.displayName,
        file.fileName,
        file.name,
        file.resourceKey,
        file.previewUrl,
        file.domainUrl,
        file.downloadUrl,
        file.ossUrl,
        file.missing ? "1" : "0",
      ]
        .filter(Boolean)
        .join("@");
    })
    .filter(Boolean)
    .join(",");
}

export function getTaskRenderSignature(
  task: RenderableTask,
  baseId: string
): string {
  const resultMap = task.resultMap || {};
  const searchResult = resultMap.searchResult;
  const plan = task.plan;
  const artifactRefs = Array.isArray(task.artifactRefs) ? task.artifactRefs : [];
  const toolCallTargetName = resolveToolCallTargetName(
    resultMap as unknown as MESSAGE.ResultMap | undefined
  );
  const querySignature = Array.isArray(searchResult?.query)
    ? searchResult.query.join("||")
    : "";
  const docsSignature = Array.isArray(searchResult?.docs)
    ? searchResult.docs
      .map((docs: MESSAGE.Doc[] | MESSAGE.Doc) => (Array.isArray(docs) ? docs.length : 0))
      .join(",")
    : "";

  // 签名必须覆盖 tool/artifact/query/plan 等展示字段；否则同一个任务对象原地更新时 WeakMap 会返回过期结果。
  return [
    baseId,
    task.messageId || "",
    task.messageType || "",
    task.messageType === "deep_search" ? "" : task.messageTime || "",
    resultMap.messageType || "",
    resultMap.isFinal ? "1" : "0",
    resultMap.searchFinish ? "1" : "0",
    resultMap.status || "",
    resultMap.summary || "",
    resultMap.toolName ||
      (resultMap as { resultMap?: { toolName?: string } }).resultMap?.toolName ||
      "",
    resultMap.toolCallId ||
      (resultMap as { resultMap?: { toolCallId?: string } }).resultMap
        ?.toolCallId ||
      "",
    Array.isArray(resultMap.subAgentProgressLines)
      ? `${resultMap.subAgentProgressLines.length}:${resultMap.subAgentProgressLines[resultMap.subAgentProgressLines.length - 1] || ""}`
      : 0,
    String(resultMap.subAgentLiveText || ""),
    resultMap.subAgentElapsedMs ?? "",
    // 流式入参必须能反映内容变化，但全文进签名会让每帧成本随 payload 增长。
    // 用「长度 + 内容哈希」：见 hashTextForSignature 的说明。
    typeof resultMap.argumentsText === "string"
      ? hashTextForSignature(resultMap.argumentsText)
      : "",
    typeof (resultMap as { resultMap?: { argumentsText?: string } }).resultMap
      ?.argumentsText === "string"
      ? hashTextForSignature(
        (resultMap as { resultMap?: { argumentsText?: string } }).resultMap!
          .argumentsText!
      )
      : "",
    toolCallTargetName,
    task.toolThought || "",
    resultMap.answer || "",
    resultMap.codeOutput?.length || 0,
    resultMap.data?.length || 0,
    artifactRefs.length,
    artifactRefs[0]?.resourceKey || artifactRefs[0]?.previewUrl || artifactRefs[0]?.downloadUrl || "",
    getArtifactSignature(task),
    querySignature,
    docsSignature,
    getDeepSearchFactsSignature(resultMap as Record<string, unknown>) ||
      getChaptersSignature(resultMap as MESSAGE.ResultMap),
    Array.isArray(plan?.stepStatus) ? plan.stepStatus.join(",") : "",
  ].join("|");
}

/**
 * 兼容层：普通任务只做浅快照。Deep Research 已由 canonicalizer 负责
 * 阶段拆分和稳定身份，不再通过这里的 isolateResultMap 分支二次解释。
 */
function createRenderTask(
  task: RenderableTask,
  id: string,
  searchResult?: RenderSearchResult,
): CHAT.Task {
  const baseResultMap = task.resultMap ? { ...task.resultMap } : task.resultMap;
  const nextTask = {
    ...task,
    id,
    __timelineRendered: true,
    resultMap: baseResultMap,
    plan: clonePlanForRender(task.plan),
  } as unknown as CHAT.Task;

  if (searchResult && nextTask.resultMap) {
    nextTask.resultMap.searchResult = searchResult as CHAT.Task["resultMap"]["searchResult"];
  }

  return nextTask;
}

export function processTaskForRender(
  task: RenderableTask,
  baseId: string
): CHAT.Task[] {
  if (
    (task as CHAT.Task & { __timelineRendered?: boolean }).__timelineRendered
  ) {
    return [task as CHAT.Task];
  }
  const signature = getTaskRenderSignature(task, baseId);
  const cached = taskRenderCache.get(task);
  if (cached?.signature === signature) {
    // 同一对象且展示输入未变化时复用拆分结果，避免每个 SSE chunk 都重复生成任务数组。
    return cached.items;
  }

  let items: CHAT.Task[];
  if (task.messageType === "deep_search") {
    items = canonicalizeDeepSearchStages(task, baseId).map((card) => card.task);
  } else {
    items = [createRenderTask(task, baseId)];
  }

  taskRenderCache.set(task, {
    signature,
    items,
  });

  return items;
}
