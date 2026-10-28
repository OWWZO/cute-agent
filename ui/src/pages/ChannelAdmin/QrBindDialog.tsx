import { Loader2, RefreshCw, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { QrPhase } from "./useChannelBindings";

interface QrBindDialogProps {
  open: boolean;
  qrUrl: string | null;
  phase: QrPhase;
  error: string | null;
  onClose: () => void;
  onRetry: () => void;
}

/**
 * 扫码弹层。
 *
 * 二维码用外部渲染服务生成——项目里没有 QR 编码库，为一个弹层引入新依赖不划算。
 * 生成的 SVG 只包含后端返回的 task_id，没有敏感信息。
 */
export default function QrBindDialog({
  open,
  qrUrl,
  phase,
  error,
  onClose,
  onRetry,
}: QrBindDialogProps) {
  if (!open) {
    return null;
  }

  const qrImage = qrUrl
    ? `https://api.qrserver.com/v1/create-qr-code/?size=240x240&margin=8&data=${encodeURIComponent(qrUrl)}`
    : null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="扫码绑定机器人"
    >
      <div className="w-full max-w-sm rounded-xl bg-background p-6 shadow-xl">
        <div className="mb-4 flex items-start justify-between">
          <div>
            <h2 className="text-base font-semibold">扫码绑定</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              用手机 QQ 扫描下方二维码并确认
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="rounded p-1 text-muted-foreground hover:bg-muted"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="flex min-h-[240px] items-center justify-center">
          {phase === "loading" || phase === "idle" ? (
            <Loader2 className="size-6 animate-spin text-muted-foreground" />
          ) : phase === "expired" ? (
            <div className="text-center">
              <p className="text-sm text-destructive">
                {error || "二维码已失效"}
              </p>
              <Button
                variant="outline"
                size="sm"
                className="mt-3"
                onClick={onRetry}
              >
                <RefreshCw className="mr-1 size-3.5" />
                重新生成
              </Button>
            </div>
          ) : qrImage ? (
            <img
              src={qrImage}
              alt="绑定二维码"
              width={240}
              height={240}
              className="rounded-lg border bg-white p-2"
            />
          ) : null}
        </div>

        {phase === "waiting" ? (
          <p className="mt-4 flex items-center justify-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            等待扫码确认…
          </p>
        ) : null}

        {qrUrl ? (
          <p className="mt-3 break-all text-center text-[11px] text-muted-foreground">
            扫码失败可复制链接在手机 QQ 打开：{qrUrl}
          </p>
        ) : null}
      </div>
    </div>
  );
}
