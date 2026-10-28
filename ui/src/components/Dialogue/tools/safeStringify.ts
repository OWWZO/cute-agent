/**
 * `JSON.stringify` 的安全版本。
 *
 * 工具结果来自模型/远端，可能含循环引用或 BigInt —— 直接 stringify 会抛异常，
 * 把整张工具卡炸掉；这里退化成固定占位文本。
 */
export function safeStringify(value: unknown, space = 2): string {
  try {
    return JSON.stringify(value, null, space) ?? "";
  } catch {
    return "[无法序列化的结果]";
  }
}
