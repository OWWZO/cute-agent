import React, { useCallback, useEffect, useMemo, useState, type SyntheticEvent } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { CircleAlert, FileAudio, FileVideo, TriangleAlert } from "lucide-react";
import { normalizeFileUrlForBrowser } from "@/utils/fileUrl";
import { cn } from "@/lib/utils";
import Loading from "./Loading";

const ERROR_CLASS =
  "m-6 min-w-[260px] max-w-[calc(100%-48px)] [&_[data-slot=alert-description]]:break-words [&_[data-slot=alert-description]]:whitespace-normal";

interface MediaFileRendererProps {
  /** 播放类型。 */
  kind: "video" | "audio";
  fileUrl: string;
  fileName?: string;
  missingReason?: string;
  className?: string;
}

/**
 * 视频 / 音频预览。
 *
 * 三态：
 * - `loading`   —— 元数据尚未加载完成（浏览器正在拉流）
 * - `unavailable` —— 没有可用 URL / 后端标记资源缺失
 * - `unsupported` —— 浏览器能拿到资源但解码失败（编码不支持，或容器损坏）
 *
 * 音视频本身由浏览器直接播放，不需要预取二进制，因此这里不做 fetch。
 */
const MediaFileRenderer: CuteType.FC<MediaFileRendererProps> = React.memo((props) => {
  const { kind, fileUrl, fileName, missingReason, className } = props;

  const resolvedUrl = useMemo(
    () => normalizeFileUrlForBrowser(fileUrl || ""),
    [fileUrl]
  );

  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");

  // URL 变化（切换文件 / 重新生成链接）时回到加载态，避免沿用上一个媒体的状态。
  useEffect(() => {
    setStatus("loading");
  }, [resolvedUrl]);

  const handleLoadedMetadata = useCallback(() => {
    setStatus("ready");
  }, []);

  const handleError = useCallback((event: SyntheticEvent<HTMLMediaElement>) => {
    console.error("[MediaFileRenderer] 媒体加载失败", event.currentTarget.error);
    setStatus("error");
  }, []);

  const renderError = (params: {
    variant: React.ComponentProps<typeof Alert>["variant"];
    icon: React.ReactNode;
    title: string;
    description: string;
  }) => (
    <div className={cn("flex h-full items-center justify-center bg-white p-6", className)}>
      <Alert
        variant={params.variant}
        className={ERROR_CLASS}
      >
        {params.icon}
        <AlertTitle>{params.title}</AlertTitle>
        <AlertDescription>{params.description}</AlertDescription>
      </Alert>
    </div>
  );

  if (missingReason || !resolvedUrl) {
    return renderError({
      variant: "destructive",
      icon: <CircleAlert />,
      title: kind === "video" ? "视频不可播放" : "音频不可播放",
      description: missingReason || "引用资源不存在或已失效",
    });
  }

  if (status === "error") {
    return renderError({
      variant: "warning",
      icon: <TriangleAlert />,
      title: kind === "video" ? "无法播放该视频" : "无法播放该音频",
      description: "当前浏览器不支持该文件使用的编码，或文件已损坏。请下载后用本地播放器打开。",
    });
  }

  const Icon = kind === "video" ? FileVideo : FileAudio;

  return (
    <div
      className={cn(
        "relative flex h-full min-h-0 items-center justify-center overflow-auto bg-white p-6",
        className
      )}
    >
      {status === "loading" ? (
        <div className="absolute inset-0 flex items-center justify-center bg-white/70">
          <Loading />
        </div>
      ) : null}

      <div className="flex w-full max-w-[920px] flex-col items-center gap-3">
        {kind === "video" ? (
          <video
            src={resolvedUrl}
            controls
            playsInline
            preload="metadata"
            onLoadedMetadata={handleLoadedMetadata}
            onError={handleError}
            className="max-h-[min(72vh,860px)] w-full rounded-lg bg-black"
          >
            当前浏览器不支持内嵌视频播放。
          </video>
        ) : (
          <audio
            src={resolvedUrl}
            controls
            preload="metadata"
            onLoadedMetadata={handleLoadedMetadata}
            onError={handleError}
            className="w-full max-w-2xl"
          >
            当前浏览器不支持内嵌音频播放。
          </audio>
        )}

        {fileName ? (
          <div className="flex max-w-full items-center gap-1.5 text-ui-sm text-[var(--chat-text-muted)]">
            <Icon className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">{fileName}</span>
          </div>
        ) : null}
      </div>
    </div>
  );
});

MediaFileRenderer.displayName = "MediaFileRenderer";

export default MediaFileRenderer;
