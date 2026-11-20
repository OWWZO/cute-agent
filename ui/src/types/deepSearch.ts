export type DeepSearchCardItemKind = "result" | "query";

export type DeepSearchCardItem = {
  name: string;
  pageContent: string;
  url: string;
  kind?: DeepSearchCardItemKind;
  interactive?: boolean;
  metaLabel?: string;
};

export type DeepSearchStage = "extend" | "search" | "chapter_summary" | "report";

export type DeepSearchPreviewStage = Exclude<DeepSearchStage, "report">;

export type DeepSearchPreviewModel = {
  stage: DeepSearchPreviewStage;
  query: string;
  statusLabel: string;
  description: string;
  loading: boolean;
  interactive: boolean;
  resultCount: number;
  hasSummary?: boolean;
  summary?: string;
  summaryStreaming?: boolean;
  sources?: DeepSearchCardItem[];
};

/** 时间线卡片模型。报告阶段也使用同一张卡，不切换组件树。 */
export type DeepSearchTimelineCardModel = Omit<
  DeepSearchPreviewModel,
  "stage"
> & {
  stage: DeepSearchStage;
};

export type DeepSearchChapterWorkspaceModel = {
  title: string;
  summary: string;
  sources: DeepSearchCardItem[];
  order?: number;
  isStreaming?: boolean;
};
