export type FileKind =
  | "img"
  | "xlsx"
  | "md"
  | "html"
  | "pdf"
  | "ppt"
  | "css"
  | "code"
  | "py"
  | "file";

/** 文件类型角标底色/字色。走语义 token，避免写死 hex。 */
export const FILE_KIND_TONE = "bg-hover text-foreground-subtle";

export function resolveFileExtension(type?: string, name?: string): string {
  return (type || name?.split(".").pop() || "").toLowerCase();
}

// 优先使用后端提供的类型，缺失时才从文件名后缀推断，保证展示和下载协议一致。
export function resolveFileKind(type?: string, name?: string): FileKind {
  const ext = resolveFileExtension(type, name);
  if (["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif"].includes(ext)) {
    return "img";
  }
  if (["csv", "xlsx", "xls"].includes(ext)) return "xlsx";
  if (["md", "markdown", "txt"].includes(ext)) return "md";
  if (["html", "htm"].includes(ext)) return "html";
  if (ext === "pdf") return "pdf";
  if (["ppt", "pptx", "pps", "ppsx"].includes(ext)) return "ppt";
  if (["css", "scss", "less"].includes(ext)) return "css";
  if (ext === "py") return "py";
  if (["js", "ts", "tsx", "jsx", "java", "json", "xml", "code"].includes(ext)) {
    return "code";
  }
  return "file";
}

export function fileKindLabel(type?: string, name?: string) {
  const kind = resolveFileKind(type, name);
  const ext = resolveFileExtension(type, name).toUpperCase();
  switch (kind) {
    case "img":
      return `Image · ${ext}`;
    case "xlsx":
      return `Spreadsheet · ${ext}`;
    case "md":
      return name?.replace(/\.[^.]+$/, "") || `Document · ${ext || "MD"}`;
    case "html":
      return "Web page · HTML";
    case "pdf":
      return "PDF · PDF";
    case "ppt":
      return `演示文稿 · ${ext}`;
    case "css":
      return "样式文件 · CSS";
    case "py":
      return "Python · PY";
    case "code":
      return `Code · ${ext}`;
    default:
      return ext ? `File · ${ext}` : "File";
  }
}

// 徽标是列表中的紧凑视觉标记，具体文件类型名称由 fileKindLabel 单独负责。
export function fileKindBadge(kind: FileKind) {
  if (kind === "xlsx") return "X";
  if (kind === "py") return "Py";
  if (kind === "html" || kind === "code") return "</>";
  if (kind === "img") return "▢";
  if (kind === "pdf") return "P";
  if (kind === "ppt") return "P/";
  return "T";
}
