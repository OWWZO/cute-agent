/**
 * 把 PPTX 的幻灯片节点树展开成可命中的「元素模型」，供选择模式做命中测试与高亮。
 *
 * 移植自 ZCode `packages/ui/src/presentation/presentationElementModel.ts`。
 */

import type {
  PresentationElementBounds,
  PresentationElementNodeType,
  PresentationPageElement,
} from "./types";

interface PresentationElementCellInput {
  gridSpan: number;
  rowSpan: number;
  hMerge: boolean;
  vMerge: boolean;
  text?: string;
}

interface PresentationElementRowInput {
  height: number;
  cells: readonly PresentationElementCellInput[];
}

interface PresentationElementNodeInput {
  id: string;
  name: string;
  nodeType: "shape" | "picture" | "table" | "group" | "chart" | "unknown";
  position: { x: number; y: number };
  size: { w: number; h: number };
  text?: string;
  columns?: readonly number[];
  rows?: readonly PresentationElementRowInput[];
}

interface PresentationGroupTextEntryInput {
  nodeId: string;
  nodePath: string;
  text: string;
  bounds: { x: number; y: number; w: number; h: number };
}

function toBounds(node: PresentationElementNodeInput): PresentationElementBounds {
  return {
    x: node.position.x,
    y: node.position.y,
    width: node.size.w,
    height: node.size.h,
  };
}

function buildTableCellElements(
  node: PresentationElementNodeInput,
  base: Omit<PresentationPageElement, "bounds" | "nodeType">,
): PresentationPageElement[] {
  const columns = node.columns;
  const rows = node.rows;
  if (!columns || !rows || columns.length === 0 || rows.length === 0) {
    return [];
  }

  // 一次前缀和：列宽 / 行高的累加、单元格区间和都降为 O(1)，不再反复 slice + reduce。
  const columnPrefix: number[] = [0];
  for (const width of columns) {
    columnPrefix.push((columnPrefix[columnPrefix.length - 1] ?? 0) + Math.max(0, width));
  }
  const rowPrefix: number[] = [0];
  for (const row of rows) {
    rowPrefix.push((rowPrefix[rowPrefix.length - 1] ?? 0) + Math.max(0, row.height));
  }
  const columnTotal = columnPrefix[columnPrefix.length - 1] ?? 0;
  const rowTotal = rowPrefix[rowPrefix.length - 1] ?? 0;
  if (columnTotal <= 0 || rowTotal <= 0) {
    return [];
  }

  const columnOffsets = columns.map((_, index) => columnPrefix[index] ?? 0);
  const rowOffsets = rows.map((_, index) => rowPrefix[index] ?? 0);

  return rows.flatMap((row, rowIndex) => {
    let columnIndex = 0;
    return row.cells.flatMap((cell, cellIndex) => {
      const gridSpan = Math.max(1, cell.gridSpan || 1);
      if (cell.hMerge || cell.vMerge) {
        // 原因：pptx-renderer 对纵向 merge continuation 仍推进 gridSpan，横向
        // continuation 则不推进；直接使用物理 cellIndex 会把后续 overlay 左移。
        // hMerge + vMerge 同时出现时按横向 continuation 处理，假定合并起点
        // 的 gridSpan 已覆盖这些列。
        if (cell.vMerge && !cell.hMerge) {
          columnIndex += gridSpan;
        }
        return [];
      }
      const startColumnIndex = columnIndex;
      columnIndex += gridSpan;
      const rowSpan = Math.max(1, cell.rowSpan || 1);
      const columnEnd = Math.min(startColumnIndex + gridSpan, columns.length);
      const rowEnd = Math.min(rowIndex + rowSpan, rows.length);
      const cellWidth =
        (columnPrefix[columnEnd] ?? 0) - (columnPrefix[startColumnIndex] ?? 0);
      const cellHeight = (rowPrefix[rowEnd] ?? 0) - (rowPrefix[rowIndex] ?? 0);
      return [
        {
          ...base,
          nodeType: "table-cell" as const,
          rowIndex,
          cellIndex,
          text: cell.text,
          bounds: {
            x:
              node.position.x +
              ((columnOffsets[startColumnIndex] ?? 0) / columnTotal) * node.size.w,
            y: node.position.y + ((rowOffsets[rowIndex] ?? 0) / rowTotal) * node.size.h,
            width: (cellWidth / columnTotal) * node.size.w,
            height: (cellHeight / rowTotal) * node.size.h,
          },
        },
      ];
    });
  });
}

export function buildPresentationPageElements(options: {
  slideIndex: number;
  slidePart: string;
  nodes: readonly PresentationElementNodeInput[];
  groupTextEntries: readonly PresentationGroupTextEntryInput[];
}): PresentationPageElement[] {
  const directElements = options.nodes.flatMap((node, zIndex) => {
    if (node.nodeType === "group" || node.nodeType === "unknown") {
      return [];
    }
    const nodeType = node.nodeType as Exclude<PresentationElementNodeType, "table-cell">;
    const base = {
      slideIndex: options.slideIndex,
      slidePart: options.slidePart,
      nodeId: node.id,
      nodeName: node.name,
      nodeType,
      bounds: toBounds(node),
      zIndex,
      ...(node.text ? { text: node.text } : {}),
    } satisfies PresentationPageElement;
    return node.nodeType === "table" ? [base, ...buildTableCellElements(node, base)] : [base];
  });

  const directIds = new Set(
    options.nodes.filter((node) => node.nodeType !== "group").map((node) => node.id),
  );
  const groupTextElements = options.groupTextEntries.flatMap((entry) => {
    if (directIds.has(entry.nodeId)) {
      return [];
    }
    const pathSegments = entry.nodePath.split("/");
    const nodesSegmentIndex = pathSegments.indexOf("nodes");
    // buildTextIndex 的真实路径是 slides/{slide}/nodes/{groupId}/children/...；
    // 兼容旧测试/调用方的首段 groupId，避免所有组内文本都错误落到 zIndex 0。
    const groupId =
      (nodesSegmentIndex >= 0 ? pathSegments[nodesSegmentIndex + 1] : undefined) ?? pathSegments[0];
    const zIndex = Math.max(
      0,
      options.nodes.findIndex((node) => node.nodeType === "group" && node.id === groupId),
    );
    return [
      {
        slideIndex: options.slideIndex,
        slidePart: options.slidePart,
        nodeId: entry.nodeId,
        nodePath: entry.nodePath,
        nodeName: "",
        nodeType: "shape" as const,
        text: entry.text,
        bounds: {
          x: entry.bounds.x,
          y: entry.bounds.y,
          width: entry.bounds.w,
          height: entry.bounds.h,
        },
        zIndex,
      },
    ];
  });

  return [...directElements, ...groupTextElements];
}

function containsPoint(bounds: PresentationElementBounds, point: { x: number; y: number }) {
  return (
    point.x >= bounds.x &&
    point.y >= bounds.y &&
    point.x <= bounds.x + bounds.width &&
    point.y <= bounds.y + bounds.height
  );
}

export function hitTestPresentationElement(
  elements: readonly PresentationPageElement[],
  point: { x: number; y: number },
): PresentationPageElement | null {
  const matches = elements.filter((element) => containsPoint(element.bounds, point));
  return (
    matches.sort((left, right) => {
      const cellPriority =
        Number(right.nodeType === "table-cell") - Number(left.nodeType === "table-cell");
      return cellPriority || right.zIndex - left.zIndex;
    })[0] ?? null
  );
}
