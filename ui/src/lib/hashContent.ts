/**
 * 短字符串哈希（djb2a，XOR 变体），用于生成缓存 key / 内容签名。
 *
 * 不是密码学哈希，也不是唯一标识：输出只有 32 位，短输入也可能碰撞
 * （例如 `o4r` 与 `o60`）。调用方必须保留全文比较，或额外拼接长度/版本盐。
 *
 * 抽在这里是因为 codeViewer 的 cacheKey 和 renderTasks 的渲染签名
 * 都用同一套算法 —— 两处各写一遍会各自漂移，而它们对碰撞的容忍度
 * 是一样的。
 */
export function hashContent(value: string): string {
  let hash = 5381;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 33) ^ value.charCodeAt(index);
  }
  return (hash >>> 0).toString(36);
}
