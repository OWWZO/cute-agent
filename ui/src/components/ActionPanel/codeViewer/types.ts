import type { SelectedLineRange as DiffsSelectedLineRange } from "@pierre/diffs";
import type { CSSProperties, HTMLAttributes, ReactNode, Ref } from "react";

/**
 * 代码评论能力相关类型。
 *
 * 移植自 ZCode `packages/ui/src/components/ui/code-viewer.tsx`（790 行封装），
 * 底层同样是 `@pierre/diffs` 的 `File`。
 */

/**
 * 评论范围。行号从 1 开始，使用文件绝对行号（与 `firstLineNumber` 同坐标）；
 * 起始行不得晚于结束行。
 */
export interface CodeCommentRange {
  startLine: number;
  endLine: number;
}

export interface CodeCommentPreview extends CodeCommentRange {
  id: string;
  comment: string;
}

export interface CodeCommentLabels {
  addComment: string;
  addCommentTooltip: string;
  commentPlaceholder: string;
  submitComment: string;
  cancelComment: string;
  deleteComment: string;
  /** `{line}` 占位。 */
  commentLine: string;
  /** `{startLine}` / `{endLine}` 占位。 */
  commentRange: string;
}

/** `renderAnnotation` 收到的元数据：已提交评论 or 正在编辑的草稿。 */
export type CodeViewerAnnotationMetadata =
  | { kind: "comment"; comment: CodeCommentPreview }
  | { kind: "draft"; range: CodeCommentRange };

export type CodeViewerStyle = CSSProperties & {
  "--code-comment-add-tooltip"?: string;
  "--diffs-bg"?: string;
  "--diffs-light-bg"?: string;
  "--diffs-dark-bg"?: string;
  "--diffs-font-family"?: string;
  "--diffs-font-size"?: string;
};

/**
 * `@pierre/diffs` 的 `SelectedLineRange` 子集（本项目只用行号，不关心 side）。
 *
 * `start` / `end` 是相对文件的 **1 基**行号；叠加 `firstLineNumber` 换算成
 * 内部 `CodeCommentRange` 见 `utils.ts` 的 `selectedRangeToCodeCommentRange`。
 */
export type SelectedLineRange = Pick<DiffsSelectedLineRange, "start" | "end">;

export interface CodeViewerProps extends HTMLAttributes<HTMLDivElement> {
  code: string;
  language: string;
  enableSyntaxHighlighting?: boolean;
  showLineNumbers?: boolean;
  theme?: string;
  /**
   * 显式指定换行。**不传时读全局偏好**（`useCodePreviewWrap`），
   * 传了则以传入值为准（受控），此时不显示内置换行开关。
   */
  wrapLongLines?: boolean;
  fontSizePx?: number;
  firstLineNumber?: number;
  comments?: readonly CodeCommentPreview[];
  /**
   * 当前文件身份。变化时清空未提交的评论草稿，避免把旧文件的行号带到新文件。
   * 缺省时回退到内容缓存键（主题切换也会重建缓存键）。
   */
  commentResetKey?: string;
  /**
   * 要滚动到的评论范围。必须与 `focusRequestId` 同时传入；任一缺失都不会聚焦。
   */
  focusedRange?: CodeCommentRange | null;
  /**
   * 聚焦请求的标识，同时用作评论 id：必须与评论的 `data-code-comment-id` 一致，
   * 否则只能退化为按行号滚动。重复聚焦时可通过改变或先清空再恢复 `focusedRange`
   * 触发 effect。
   */
  focusRequestId?: string;
  /**
   * 需要点名的行号（1 起，与 `firstLineNumber` 同坐标）：只把这些行的**行号**染成警示色，
   * 代码本身不动。
   */
  markedLines?: readonly number[];
  enableLineSelection?: boolean;
  enableGutterUtility?: boolean;
  labels?: Partial<CodeCommentLabels>;
  onSubmitCodeComment?: (params: {
    range: CodeCommentRange;
    selectedText: string;
    comment: string;
  }) => void;
  onDeleteCodeComment?: (commentId: string) => void;
  scrollContainerRef?: Ref<HTMLDivElement>;
  /** 不显示右上角的自动换行开关（例如外层已有同义控件时）。 */
  hideWrapToggle?: boolean;
  /**
   * 与自动换行开关同排渲染的额外控件（右上角浮动条）。
   * 用于「图形/源码」这类只有调用方才知道语义的切换，避免各自绝对定位打架。
   */
  headerActions?: ReactNode;
}
