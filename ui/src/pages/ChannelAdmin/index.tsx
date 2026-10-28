import { useState } from "react";
import {
  Bot,
  ChevronDown,
  ChevronRight,
  Loader2,
  RefreshCw,
  Unlink,
} from "lucide-react";
import WorkspaceAdminHeader from "@/components/WorkspaceAdminHeader";
import { Button } from "@/components/ui/button";
import { confirm } from "@/components/ui/confirm-dialog";
import { cn } from "@/lib/utils";
import type { ChannelBindingStatus } from "@/services/channel";
import QrBindDialog from "./QrBindDialog";
import { useChannelBindings } from "./useChannelBindings";

const STATUS_STYLE: Record<ChannelBindingStatus, string> = {
  ACTIVE: "bg-emerald-500",
  ERROR: "bg-destructive",
  PENDING: "bg-amber-500",
  DISABLED: "bg-muted-foreground",
};

const STATUS_LABEL: Record<ChannelBindingStatus, string> = {
  ACTIVE: "已连接",
  ERROR: "连接失败",
  PENDING: "连接中",
  DISABLED: "已停用",
};

interface ChannelAdminProps {
  embedded?: boolean;
}

export default function ChannelAdmin({ embedded }: ChannelAdminProps) {
  const {
    loading,
    mine,
    qr,
    qrPhase,
    qrError,
    sessions,
    sessionsLoading,
    startBind,
    closeQr,
    retry,
    unbind,
    loadSessions,
  } = useChannelBindings();
  const [sessionsOpen, setSessionsOpen] = useState(false);

  const binding = mine?.binding ?? null;

  const handleUnbind = () => {
    confirm({
      title: "解绑机器人",
      content:
        "解绑后机器人会下线，QQ 侧无法再接续之前的对话。历史会话仍可在 Web UI 查看。",
      okText: "解绑",
      danger: true,
      onOk: unbind,
    });
  };

  const toggleSessions = async () => {
    const next = !sessionsOpen;
    setSessionsOpen(next);
    if (next) {
      await loadSessions();
    }
  };

  return (
    <div className="flex h-full flex-col">
      <WorkspaceAdminHeader
        title="机器人管理"
        description="绑定你自己的 QQ 机器人，任何 QQ 用户都可以私聊或 @ 它对话"
        icon={Bot}
        embedded={embedded}
      />

      <div className="flex-1 overflow-auto p-6">
        <div className="mx-auto max-w-2xl space-y-4">
          {loading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" />
              加载中…
            </div>
          ) : binding ? (
            <div className="rounded-xl border p-5">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span
                      className={cn(
                        "size-2 rounded-full",
                        STATUS_STYLE[binding.status]
                      )}
                      aria-hidden="true"
                    />
                    <span className="text-sm font-medium">
                      {STATUS_LABEL[binding.status]}
                    </span>
                  </div>
                  <p className="mt-2 truncate text-xs text-muted-foreground">
                    AppID：{binding.appId}
                  </p>
                  {binding.connectedAt ? (
                    <p className="mt-1 text-xs text-muted-foreground">
                      连接时间：{binding.connectedAt}
                    </p>
                  ) : null}
                  {binding.lastError ? (
                    <p className="mt-1 text-xs text-destructive">
                      {binding.lastError}
                    </p>
                  ) : null}
                </div>

                <div className="flex shrink-0 gap-2">
                  {binding.status === "ERROR" ? (
                    <Button variant="outline" size="sm" onClick={() => void retry()}>
                      <RefreshCw className="mr-1 size-3.5" />
                      重试
                    </Button>
                  ) : null}
                  <Button variant="outline" size="sm" onClick={handleUnbind}>
                    <Unlink className="mr-1 size-3.5" />
                    解绑
                  </Button>
                </div>
              </div>

              <button
                type="button"
                onClick={() => void toggleSessions()}
                className="mt-4 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
              >
                {sessionsOpen ? (
                  <ChevronDown className="size-3.5" />
                ) : (
                  <ChevronRight className="size-3.5" />
                )}
                查看会话
              </button>

              {sessionsOpen ? (
                <div className="mt-2 rounded-lg border bg-muted/30">
                  {sessionsLoading ? (
                    <div className="flex items-center gap-2 p-3 text-xs text-muted-foreground">
                      <Loader2 className="size-3.5 animate-spin" />
                      加载中…
                    </div>
                  ) : sessions.length === 0 ? (
                    <p className="p-3 text-xs text-muted-foreground">
                      还没有人跟这个机器人对话
                    </p>
                  ) : (
                    <ul className="divide-y">
                      {sessions.map((item) => (
                        <li
                          key={item.sessionId}
                          className="flex items-center justify-between gap-3 px-3 py-2"
                        >
                          <span className="min-w-0 truncate text-xs">
                            {item.title || "新对话"}
                          </span>
                          <span className="shrink-0 text-[11px] text-muted-foreground">
                            {item.runCount} 轮 · {item.lastActiveAt || "-"}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ) : null}
            </div>
          ) : (
            <div className="rounded-xl border p-6 text-center">
              <p className="text-sm text-muted-foreground">还没有绑定机器人</p>
              <Button className="mt-4" onClick={() => void startBind()}>
                扫码绑定
              </Button>
              <p className="mt-3 text-xs text-muted-foreground">
                已用 {mine?.globalBindingCount ?? 0}/{mine?.maxBindings ?? 0}
              </p>
            </div>
          )}
        </div>
      </div>

      <QrBindDialog
        open={qrPhase !== "idle"}
        qrUrl={qr?.qrUrl ?? null}
        phase={qrPhase}
        error={qrError}
        onClose={closeQr}
        onRetry={() => void startBind()}
      />
    </div>
  );
}
