/**
 * PPTX 预览引擎的公共类型。
 *
 * 移植自 ZCode `packages/ui/src/presentation/types.ts`，保持结构一致以便后续
 * 与上游对齐；这里只保留本项目实际用到的部分。
 */

export interface PresentationPageSize {
  width: number;
  height: number;
}

export interface PresentationRenderOptions {
  onNavigate?: (target: { pageIndex?: number; url?: string }) => void;
  /**
   * 单个节点渲染失败时调用；可能对每个失败节点触发一次。未提供时由底层
   * renderer 自行处理，调用方不会收到该失败通知。
   */
  onNodeError?: (nodeId: string, error: unknown) => void;
}

export interface PresentationRenderHandle {
  /**
   * 当前页渲染完成的 Promise。渲染失败时会 reject，调用方必须 await/catch；
   * 不要只调用 dispose 后忽略它。
   */
  readonly ready: Promise<void>;
  /**
   * 释放当前页资源；可重复调用。它不会显式等待或改写 `ready` 的状态。
   */
  dispose(): void;
}

export type PresentationElementNodeType = "shape" | "picture" | "chart" | "table" | "table-cell";

export interface PresentationElementBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PresentationPageElement {
  slideIndex: number;
  slidePart: string;
  nodeId: string;
  nodePath?: string;
  nodeName: string;
  nodeType: PresentationElementNodeType;
  text?: string;
  bounds: PresentationElementBounds;
  zIndex: number;
  /** 仅 `table-cell` 节点保证携带；从 0 开始。 */
  rowIndex?: number;
  /** 仅 `table-cell` 节点保证携带；从 0 开始。 */
  cellIndex?: number;
}

export interface PresentationPreviewDocument {
  readonly pageCount: number;
  readonly pageSize: PresentationPageSize;
  /** 页码从 0 开始；越界时抛 `RangeError`，文档已 dispose 时抛错。 */
  getPageElements(pageIndex: number): readonly PresentationPageElement[];
  /** 页码从 0 开始；越界时抛 `RangeError`，文档已 dispose 时抛错。 */
  renderPage(
    pageIndex: number,
    container: HTMLElement,
    options?: PresentationRenderOptions,
  ): PresentationRenderHandle;
  dispose(): void;
}

export interface PresentationPreviewEngine {
  open(data: ArrayBuffer): Promise<PresentationPreviewDocument>;
}
