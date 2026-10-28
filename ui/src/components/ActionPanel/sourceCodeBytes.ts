/**
 * 源码预览的 UTF-8 字节处理。
 *
 * `String.length` 是 UTF-16 码元数，CJK 每字 3 字节、emoji 每字 4 字节；
 * 用字符数当字节数会低估体积，按字符截断还会切出半个字符。
 */

/** 估算 UTF-8 字节数。编码器不可用时按每码元最多 3 字节保守估计。 */
export function estimateSourceBytes(code: string): number {
  try {
    return new TextEncoder().encode(code).length;
  } catch {
    return code.length * 3;
  }
}

/** 按 UTF-8 字节截断；边界上被切开的多字节字符由解码器替换为 U+FFFD。 */
export function truncateSourceToBytes(code: string, maxBytes: number): string {
  const bytes = new TextEncoder().encode(code);
  if (bytes.length <= maxBytes) {
    return code;
  }
  return new TextDecoder().decode(bytes.slice(0, maxBytes));
}
