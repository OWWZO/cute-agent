/**
 * PDF 连续滚动的页偏移查找。
 *
 * 抽成纯函数：滚动热路径只做数值二分，不再逐页读 `getBoundingClientRect`
 * （几百上千页时每次 scroll 都是 O(n) 次强制布局）。
 */

/**
 * 在缓存的页偏移里找当前页。
 *
 * `offsets` 以页码为下标（1-based，`offsets[0]` 不使用），值单调不减。
 * 返回最后一个 `top <= targetTop` 的页码；目标在第一页之前时返回 1。
 */
export function findCurrentPageByOffsets(
  offsets: readonly number[],
  targetTop: number
): number {
  let low = 1;
  let high = offsets.length - 1;
  let best = 1;

  while (low <= high) {
    const mid = (low + high) >> 1;
    const top = offsets[mid];
    if (typeof top === "number" && top <= targetTop) {
      best = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  return best;
}
