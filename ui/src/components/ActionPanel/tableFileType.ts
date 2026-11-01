/** 走 xlsx 二进制解析的扩展名；其余（含 csv）先转成工作簿再交给同一个查看器。 */
export const EXCEL_EXTENSIONS = new Set(["xlsx", "xlsm", "xlsb", "xls"]);

/** 取最后一个点后的扩展名；优先文件名，文件名没有扩展名时再取 URL 的 pathname。 */
export function resolveTableExtension(
  fileName?: string,
  fileUrl?: string
): string {
  for (const source of [fileName, fileUrl]) {
    const path = (source || "").split(/[?#]/, 1)[0] || "";
    const base = path.split("/").pop() || "";
    const dotIndex = base.lastIndexOf(".");
    if (dotIndex >= 0) {
      return base.slice(dotIndex + 1).toLowerCase();
    }
  }
  return "";
}
