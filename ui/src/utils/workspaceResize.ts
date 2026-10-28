export const WORKSPACE_RESIZE_START_EVENT = "cute-workspace-resize-start";
export const WORKSPACE_RESIZE_END_EVENT = "cute-workspace-resize-end";
export const WORKSPACE_RESIZING_SELECTOR = '[data-workspace-resizing="true"]';

const isNodeLike = (value: unknown): value is Node =>
  typeof value === "object" &&
  value !== null &&
  typeof (value as Node).nodeType === "number";

export const isWorkspaceResizeEventFor = (event: Event, node: Element) => {
  const target = event.target;
  if (!isNodeLike(target)) {
    return false;
  }
  if (target === node) {
    return true;
  }
  // 事件既可能在 document 上派发（target 为 Document），也可能来自祖先或后代节点。
  // 两个方向都判断，避免只认“祖先包含子节点”导致 document 事件永远匹配失败。
  return target.contains(node) || node.contains(target);
};
