import React, { useEffect, useMemo, useRef, useState } from "react";
import { useRequest } from "ahooks";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { CircleAlert, Download } from "lucide-react";
import { downloadFile } from "@/utils";
import { normalizeFileUrlForBrowser } from "@/utils/fileUrl";
import { ViewerPanelShell } from "@/components/ui/viewer-panel-shell";
import Loading from "./Loading";
import SourceCodeView from "./SourceCodeView";
import PreviewModeToggle, { type PreviewViewMode } from "./PreviewModeToggle";
import "./svg-file-renderer.css";

const ERROR_CLASS =
  "m-6 min-w-[260px] max-w-[calc(100%-48px)] [&_[data-slot=alert-description]]:break-words [&_[data-slot=alert-description]]:whitespace-normal";

interface SvgFileRendererProps {
  fileUrl: string;
  fileName?: string;
  downloadUrl?: string;
  missingReason?: string;
  hideChrome?: boolean;
  className?: string;
}

const resolveFetchError = (error: unknown) => {
  const message = error instanceof Error ? error.message : String(error || "");
  if (
    message.includes("Failed to fetch") ||
    message.includes("Network response was not ok") ||
    message.includes("NetworkError")
  ) {
    return "引用资源不存在或已失效";
  }
  return message || "引用资源不存在或已失效";
};

/**
 * SVG 预览：图形 / 源码双模式。
 *
 * SVG 既能当图形看、也需要能当代码读（图标素材改色、检查导出内容都常用），
 * 所以不能像位图那样只给 `<img>`。图形模式用 data URL 内联，避免同源策略
 * 阻止外部 SVG 的字体 / 内嵌资源；源码模式复用 `SourceCodeView` 的 xml 高亮。
 */
const SvgFileRenderer: CuteType.FC<SvgFileRendererProps> = React.memo((props) => {
  const { fileUrl, fileName, downloadUrl, missingReason, hideChrome = false, className } = props;

  const [mode, setMode] = useState<PreviewViewMode>("preview");

  const resolvedUrl = useMemo(
    () => normalizeFileUrlForBrowser(fileUrl || ""),
    [fileUrl]
  );
  const resolvedDownload = useMemo(
    () => normalizeFileUrlForBrowser(downloadUrl || fileUrl || "") || resolvedUrl,
    [downloadUrl, fileUrl, resolvedUrl]
  );

  const abortRef = useRef<AbortController | null>(null);
  // 卸载时取消未完成的下载；切文件时由 service 自己 abort 上一个。
  useEffect(() => () => abortRef.current?.abort(), []);

  const { data: content, loading, error } = useRequest(
    async () => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      if (missingReason) throw new Error(missingReason);
      if (!resolvedUrl) throw new Error("引用资源不存在或已失效");
      const response = await fetch(resolvedUrl, { signal: controller.signal });
      if (!response.ok) throw new Error("Network response was not ok");
      return response.text();
    },
    { refreshDeps: [resolvedUrl, missingReason] }
  );

  const graphicSource = useMemo(() => {
    if (!content) return "";
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(content)}`;
  }, [content]);

  const displayError = error ? resolveFetchError(error) : null;

  const renderBody = () => {
    if (loading) {
      return (
        <div className="flex h-full min-h-[240px] items-center justify-center">
          <Loading />
        </div>
      );
    }

    if (displayError) {
      return (
        <div className="p-4">
          <Alert variant="destructive" className={ERROR_CLASS}>
            <CircleAlert />
            <AlertTitle>SVG 不可预览</AlertTitle>
            <AlertDescription>{displayError}</AlertDescription>
          </Alert>
        </div>
      );
    }

    if (mode === "source") {
      return <SourceCodeView code={content || ""} language="xml" sourcePath={fileName} />;
    }

    return (
      <div className="svg-preview-checkerboard flex h-full min-h-[240px] items-center justify-center overflow-auto p-6">
        <img
          src={graphicSource}
          alt={fileName || "SVG 预览"}
          className="h-auto max-h-full w-auto max-w-full object-contain"
        />
      </div>
    );
  };

  return (
    <ViewerPanelShell
      label="SVG"
      subtitle={fileName || "SVG 预览"}
      className={className}
      hideHeader={hideChrome}
      headerRight={
        <>
          <PreviewModeToggle mode={mode} onChange={setMode} />
          {resolvedDownload ? (
            <Button variant="ghost" size="sm" onClick={() => downloadFile(resolvedDownload, fileName)}>
              <Download className="h-3.5 w-3.5" />
              下载
            </Button>
          ) : null}
        </>
      }
      bodyClassName="min-h-0 flex-1 p-0"
    >
      {renderBody()}
    </ViewerPanelShell>
  );
});

SvgFileRenderer.displayName = "SvgFileRenderer";

export default SvgFileRenderer;
