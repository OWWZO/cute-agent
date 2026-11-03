import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import {
  channelApi,
  type ChannelBindingMineResp,
  type QrCreateResp,
} from "@/services/channel";

/** 客户端轮询上限，与后端 600 秒任务 TTL 对齐，多留 10 秒余量避免边界抖动。 */
const POLL_TIMEOUT_MS = 610_000;
const POLL_INTERVAL_MS = 2_000;

export type QrPhase = "idle" | "loading" | "waiting" | "done" | "expired";

export interface UseChannelBindingsResult {
  loading: boolean;
  mine: ChannelBindingMineResp | null;
  qr: QrCreateResp | null;
  qrPhase: QrPhase;
  qrError: string | null;
  reload: () => Promise<void>;
  startBind: () => Promise<void>;
  closeQr: () => void;
  retry: () => Promise<void>;
  unbind: () => Promise<void>;
}

/**
 * 机器人绑定页的状态机。
 *
 * 轮询必须在这四种情况下停止，否则会一直打后端：COMPLETED / EXPIRED /
 * 客户端超时 / 弹层关闭。
 */
export function useChannelBindings(): UseChannelBindingsResult {
  const [loading, setLoading] = useState(true);
  const [mine, setMine] = useState<ChannelBindingMineResp | null>(null);
  const [qr, setQr] = useState<QrCreateResp | null>(null);
  const [qrPhase, setQrPhase] = useState<QrPhase>("idle");
  const [qrError, setQrError] = useState<string | null>(null);

  const pollTimer = useRef<number | null>(null);
  const pollDeadline = useRef(0);

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      window.clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const reload = useCallback(async () => {
    try {
      const data = await channelApi.mine();
      setMine(data);
    } catch {
      // 拦截器已经给出提示；这里只保证页面不卡在 loading。
      setMine(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 组件卸载时必须停掉轮询，否则会对着已卸载组件 setState。
  useEffect(() => stopPolling, [stopPolling]);

  const poll = useCallback(
    async (taskId: string) => {
      if (Date.now() > pollDeadline.current) {
        stopPolling();
        setQrPhase("expired");
        setQrError("二维码已超时，请重新生成");
        return;
      }
      try {
        const result = await channelApi.pollQr(taskId);
        if (result.status === "COMPLETED") {
          stopPolling();
          setQrPhase("done");
          toast.success("机器人已绑定");
          await reload();
          return;
        }
        if (result.status === "EXPIRED") {
          stopPolling();
          setQrPhase("expired");
          setQrError(result.error || "二维码已过期，请重新生成");
          return;
        }
      } catch (error) {
        // 任务被后端清理（超过 10 分钟）时也会走到这里，直接终止。
        stopPolling();
        setQrPhase("expired");
        setQrError(error instanceof Error ? error.message : "扫码任务已失效");
        return;
      }
      pollTimer.current = window.setTimeout(
        () => void poll(taskId),
        POLL_INTERVAL_MS
      );
    },
    [reload, stopPolling]
  );

  const startBind = useCallback(async () => {
    stopPolling();
    setQrPhase("loading");
    setQrError(null);
    try {
      const created = await channelApi.createQr();
      setQr(created);
      setQrPhase("waiting");
      pollDeadline.current = Date.now() + POLL_TIMEOUT_MS;
      pollTimer.current = window.setTimeout(
        () => void poll(created.taskId),
        POLL_INTERVAL_MS
      );
    } catch (error) {
      setQrPhase("idle");
      // 后端把「已绑定」「已达上限」作为业务拒绝返回，info 里是可读文案。
      toast.error(error instanceof Error ? error.message : "无法生成二维码");
    }
  }, [poll, stopPolling]);

  const closeQr = useCallback(() => {
    stopPolling();
    setQr(null);
    setQrPhase("idle");
    setQrError(null);
  }, [stopPolling]);

  const retry = useCallback(async () => {
    const bindingId = mine?.binding?.bindingId;
    if (!bindingId) return;
    try {
      await channelApi.retry(bindingId);
      toast.success("正在重新连接");
      await reload();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "重试失败");
    }
  }, [mine, reload]);

  const unbind = useCallback(async () => {
    const bindingId = mine?.binding?.bindingId;
    if (!bindingId) return;
    try {
      const result = await channelApi.unbind(bindingId);
      toast.success(result.notice);
      await reload();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "解绑失败");
    }
  }, [mine, reload]);

  return {
    loading,
    mine,
    qr,
    qrPhase,
    qrError,
    reload,
    startBind,
    closeQr,
    retry,
    unbind,
  };
}
