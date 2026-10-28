/**
 * 表格预览的大小上限。
 *
 * 与 `@extend-ai/react-xlsx` 的默认上限保持一致；显式传给 `XlsxViewer`，
 * 避免库默认值变化后前端预检与查看器判定漂移。
 */
export const TABLE_MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024;

/** 文件超出在线预览上限。渲染层据此显示「文件过大」，不再进入转换/查看器路径。 */
export class TableFileTooLargeError extends Error {
  readonly byteLength: number;

  constructor(byteLength: number) {
    super("文件过大");
    this.name = "TableFileTooLargeError";
    this.byteLength = byteLength;
  }
}

/** 按 UTF-8 字节数判断，和 Content-Length / XlsxViewer 的口径一致。 */
export function isTableFileTooLarge(byteLength: number) {
  return byteLength > TABLE_MAX_FILE_SIZE_BYTES;
}

/** 文本按 UTF-8 编码后的真实字节数（CJK 与 emoji 不能用 `length` 估算）。 */
export function estimateUtf8Bytes(text: string) {
  return new TextEncoder().encode(text).length;
}
