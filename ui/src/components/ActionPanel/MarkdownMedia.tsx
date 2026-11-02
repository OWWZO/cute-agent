import { useState } from 'react';

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { ZoomableImage } from '@/components/ui/zoomable-image';
import { cn } from '@/lib/utils';
import { Expand } from 'lucide-react';

/** 终答 Markdown 内嵌图：点击放大预览 */
export const MarkdownImagePreview: CuteType.FC<{
  src: string;
  alt?: string;
  width?: number;
  height?: number;
}> = ({ src, alt, width, height }) => {
  if (!src) {
    return null;
  }
  return (
    <span className="markdown-media-image-root">
      <ZoomableImage
        src={src}
        alt={alt || '图片'}
        width={width}
        height={height}
        className="markdown-media-image"
      />
    </span>
  );
};

/** 终答 Markdown 内嵌音视频：内联播放 + 放大预览 Modal */
export const MarkdownMediaPlayer: CuteType.FC<{
  kind: 'video' | 'audio';
  src: string;
  title?: string;
}> = ({ kind, src, title }) => {
  const [open, setOpen] = useState(false);
  if (!src) {
    return null;
  }

  // 内联与放大预览只差 class / autoPlay / title，统一在这里生成，避免四处各自维护。
  // 普通函数而非嵌套组件：不参与 React 生命周期，也不会引入额外的挂载点。
  const renderMedia = (preview: boolean) => {
    const mediaProps = {
      className: `markdown-media-${kind}${preview ? '-preview' : ''}`,
      src,
      controls: true,
      preload: 'metadata' as const,
      autoPlay: preview,
      title: preview ? undefined : title,
    };
    return kind === 'video' ? <video {...mediaProps} /> : <audio {...mediaProps} />;
  };

  return (
    <>
      <div className="markdown-media-player-wrap">
        {renderMedia(false)}
        <button
          type="button"
          className="markdown-media-expand-btn"
          title="放大预览"
          aria-label="放大预览"
          onClick={() => setOpen(true)}
        >
          <Expand className="h-3.5 w-3.5" />
        </button>
      </div>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent
          className={cn(
            "markdown-media-preview-modal",
            kind === "video"
              ? "max-w-[min(920px,96vw)]"
              : "max-w-[min(520px,94vw)]"
          )}
        >
          <DialogHeader>
            <DialogTitle>
              {title || (kind === "video" ? '视频预览' : '音频预览')}
            </DialogTitle>
          </DialogHeader>
          {/* DialogContent 只在打开时挂载子节点，无需再判断 open */}
          {renderMedia(true)}
        </DialogContent>
      </Dialog>
    </>
  );
};
