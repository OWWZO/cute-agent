import React, { useEffect, useMemo, useRef } from "react";
import { useRequest } from "ahooks";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { CircleAlert } from "lucide-react";
import DocumentFallback from "./DocumentFallback";
import SourceCodeView from "./SourceCodeView";
import Loading from "./Loading";
import {
  isBinaryBackedKind,
  isTextBackedKind,
  resolveFilePreviewKind,
} from "@/lib/filePreview/kinds";
import { resolveCodeLanguage } from "@/lib/filePreview/language";
import { cn } from "@/lib/utils";

const LOADING_CLASS = "mr-32";
const ERROR_CLASS =
  "m-12 md:m-24 min-w-[260px] max-w-[calc(100%-24px)] md:max-w-[calc(100%-48px)] [&_[data-slot=alert-description]]:break-words [&_[data-slot=alert-description]]:whitespace-normal";

/** 文本预览上限：超过 2 MB 的通用文件不再整块转成字符串。 */
const MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024;

const BINARY_MIME_PREFIXES = ["image/", "audio/", "video/"];
// application/octet-stream 仍允许按文本尝试，避免误伤后端未标类型的文本文件。
const BINARY_MIME_TYPES = new Set([
  "application/pdf",
  "application/zip",
  "application/x-zip-compressed",
]);

const isBinaryContentType = (contentType: string | null) => {
  const mime = (contentType || "").split(";", 1)[0]?.trim().toLowerCase() || "";
  if (!mime) {
    return false;
  }
  return (
    BINARY_MIME_PREFIXES.some((prefix) => mime.startsWith(prefix)) ||
    BINARY_MIME_TYPES.has(mime)
  );
};

interface FileRendererProps {
  fileUrl: string;
  fileName?: string;
  downloadUrl?: string;
  missingReason?: string;
  className?: string;
  /** 强制源码高亮（忽略 HTML iframe / 图形化预览） */
  forceSource?: boolean;
}

const resolveUnavailableReason = (error: Error) => {
  const message = error?.message || "";
  if (
    message.includes("Failed to fetch") ||
    message.includes("Network response was not ok") ||
    message.includes("NetworkError")
  ) {
    return "引用资源不存在或已失效";
  }
  return message || "引用资源不存在或已失效";
};

/** 文本 / 二进制兜底渲染器；带专属预览器的类型统一由 `FilePreviewSwitch` 分派。 */
const FileRenderer: CuteType.FC<FileRendererProps> = React.memo((props) => {
  const {
    fileUrl,
    fileName,
    downloadUrl,
    missingReason,
    className,
    forceSource = false,
  } = props;

  const kind = useMemo(
    () =>
      resolveFilePreviewKind({
        name: fileName,
        url: fileUrl,
      }),
    [fileName, fileUrl]
  );
  const language = useMemo(
    () => resolveCodeLanguage(fileName || fileUrl),
    [fileName, fileUrl]
  );
  const resolvedDownload = downloadUrl || fileUrl;

  // 强制源码模式下不按类型分派，但**仍然需要把内容取回来**当纯文本读。
  // 文本类型从 `isTextBackedKind()` 派生，避免新增类型时漏取内容导致空白预览。
  const shouldFetchText =
    forceSource ||
    kind === "unknown" ||
    isTextBackedKind(kind);

  const abortRef = useRef<AbortController | null>(null);
  // 卸载时取消未完成的下载；切文件时由 service 自己 abort 上一个。
  useEffect(() => () => abortRef.current?.abort(), []);

  const { data, loading, error } = useRequest(
    async () => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      if (missingReason) {
        throw new Error(missingReason);
      }
      if (!fileUrl) {
        throw new Error("引用资源不存在或已失效");
      }
      const response = await fetch(fileUrl, { signal: controller.signal });
      if (!response.ok) {
        throw new Error("Network response was not ok");
      }
      if (isBinaryContentType(response.headers.get("content-type"))) {
        throw new Error("该文件是二进制格式，无法以文本方式预览");
      }
      const declaredSize = Number(response.headers.get("content-length"));
      if (
        Number.isFinite(declaredSize) &&
        declaredSize > MAX_TEXT_PREVIEW_BYTES
      ) {
        throw new Error("文件过大（超过 2 MB），无法预览");
      }
      const text = await response.text();
      if (
        new TextEncoder().encode(text).byteLength > MAX_TEXT_PREVIEW_BYTES
      ) {
        throw new Error("文件过大（超过 2 MB），无法预览");
      }
      return text;
    },
    {
      refreshDeps: [fileUrl, missingReason],
      ready: shouldFetchText,
    }
  );

  const renderLoading = () => (
    <div className={cn("flex h-full min-h-[200px] items-center justify-center bg-white", className)}>
      <Loading className={LOADING_CLASS} />
    </div>
  );

  const renderError = () => (
    <div className={cn("bg-white p-6", className)}>
      <Alert
        variant="destructive"
        className={ERROR_CLASS}
      >
        <CircleAlert />
        <AlertTitle>内容不可读取</AlertTitle>
        <AlertDescription>{resolveUnavailableReason(error as Error)}</AlertDescription>
      </Alert>
    </div>
  );

  // ---- 强制源码模式：任何类型都当纯文本读 ----
  if (forceSource) {
    if (loading) {
      return renderLoading();
    }
    if (error) {
      return renderError();
    }
    return (
      <SourceCodeView
        code={data || ""}
        language={language}
        className={className}
        sourcePath={fileName}
      />
    );
  }

  // ---- 二进制：引导下载 ----
  if (isBinaryBackedKind(kind)) {
    return (
      <DocumentFallback
        label={(fileName?.split(".").pop() || "FILE").toUpperCase()}
        title="该文件类型不支持在线预览"
        description={
          missingReason ||
          "二进制或办公文档无法以文本方式打开，请下载后使用本地应用查看。"
        }
        fileName={fileName}
        downloadUrl={resolvedDownload}
        className={className}
        type="info"
      />
    );
  }

  // ---- 其余（code / text / csv / diff / unknown）：按文本高亮 ----
  if (loading) {
    return renderLoading();
  }

  if (error) {
    return renderError();
  }

  return (
    <SourceCodeView
      code={data || ""}
      language={language}
      className={className}
      sourcePath={fileName}
    />
  );
});

FileRenderer.displayName = "FileRenderer";

export default FileRenderer;
