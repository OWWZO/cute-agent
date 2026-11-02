import React from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { CircleAlert, Download, Info, TriangleAlert } from "lucide-react";
import { downloadFile } from "@/utils";
import { ViewerPanelShell } from "@/components/ui/viewer-panel-shell";

const ALERT_CLASS =
  "m-0 min-w-[260px] max-w-full [&_data-[slot=alert-description]]:break-words [&_data-[slot=alert-description]]:whitespace-normal";

/** 下载地址来自后端产物；拒绝可执行协议，避免把 `javascript:` 直接塞进 `<a href>`。 */
const UNSAFE_PROTOCOL_PATTERN = /^(?:javascript|data|vbscript):/i;

const toDownloadHref = (url?: string) => {
  if (!url) {
    return undefined;
  }
  // 浏览器解析 URL 时会忽略控制字符与空白，判断协议前先按同样规则归一化。
  // 逐字符判断，避免安全正则本身触发 lint 的 no-control-regex 警告。
  let normalized = "";
  for (const character of url) {
    const codePoint = character.codePointAt(0);
    if (codePoint === undefined) continue;
    if (codePoint <= 0x20 || (codePoint >= 0x7f && codePoint <= 0x9f)) continue;
    normalized += character;
  }
  return UNSAFE_PROTOCOL_PATTERN.test(normalized) ? undefined : url;
};

interface DocumentFallbackProps {
  label?: string;
  title: string;
  description?: string;
  fileName?: string;
  downloadUrl?: string;
  className?: string;
  type?: "error" | "info" | "warning";
}

const ALERT_VARIANT = {
  error: "destructive",
  info: "info",
  warning: "warning",
} as const;

const ALERT_ICON = {
  error: CircleAlert,
  info: Info,
  warning: TriangleAlert,
} as const;

/**
 * PDF/Word 预览失败或 .doc 不支持时的统一空态 + 下载。
 */
const DocumentFallback: CuteType.FC<DocumentFallbackProps> = React.memo(
  (props) => {
    const {
      label = "DOC",
      title,
      description,
      fileName,
      downloadUrl,
      className,
      type = "info",
    } = props;

    const downloadHref = toDownloadHref(downloadUrl);
    const canDownload = Boolean(downloadHref);
    const handleDownload = () => downloadFile(downloadHref, fileName);
    const variant = ALERT_VARIANT[type];
    const Icon = ALERT_ICON[type];

    return (
      <ViewerPanelShell
        label={label}
        subtitle={fileName}
        className={className}
        headerRight={
          canDownload ? (
            <button
              type="button"
              className="inline-flex h-6 items-center gap-1 rounded-md px-2 text-ui-caption text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
              onClick={handleDownload}
            >
              <Download className="h-3.5 w-3.5" />
              下载
            </button>
          ) : null
        }
      >
        <Alert variant={variant} className={ALERT_CLASS}>
          <Icon />
          <AlertTitle>{title}</AlertTitle>
          {description ? <AlertDescription>{description}</AlertDescription> : null}
          {canDownload ? (
            <button
              type="button"
              className="mt-2 inline-flex h-8 items-center rounded-md bg-primary px-3 text-ui-caption font-medium text-primary-foreground transition-opacity hover:opacity-90"
              onClick={handleDownload}
            >
              下载原件
            </button>
          ) : null}
        </Alert>
      </ViewerPanelShell>
    );
  }
);

DocumentFallback.displayName = "DocumentFallback";

export default DocumentFallback;
