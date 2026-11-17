/**
 * 工作区标签页的返回历史。
 *
 * 只记录“前台从哪个 tab 切到哪个 tab”，不记录 tab 内容本身。
 * 关闭 tab 后的相邻兜底切换不走这里，避免返回栈被自动落点污染。
 */

export function pushTabHistory(
  stack: string[],
  previousKey: string,
  nextKey: string
): string[] {
  if (!previousKey || previousKey === nextKey) {
    return stack;
  }
  return [...stack, previousKey];
}

export function pruneTabHistory(
  stack: string[],
  openKeys: ReadonlySet<string>
): string[] {
  const next = stack.filter((key) => openKeys.has(key));
  return next.length === stack.length ? stack : next;
}

/**
 * 取出最近一个仍然打开、且不是当前 tab 的返回目标。
 * 途中遇到的已关闭 tab 会一并丢弃。
 */
export function takeTabBack(
  stack: string[],
  openKeys: ReadonlySet<string>,
  currentKey: string
): { stack: string[]; target: string } {
  const next = [...stack];
  while (next.length > 0) {
    const candidate = next.pop()!;
    if (candidate !== currentKey && openKeys.has(candidate)) {
      return { stack: next, target: candidate };
    }
  }
  return { stack: next, target: "" };
}
