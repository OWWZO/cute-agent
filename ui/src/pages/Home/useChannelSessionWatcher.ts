import { useEffect, useRef } from "react";
import { conversationHistoryApi } from "@/services/agentConversation";

/** 轮询周期：够快地发现 QQ 侧新起的轮次，又不至于压垮单行查询。 */
const POLL_INTERVAL_MS = 2500;

/**
 * 监听当前打开的渠道会话：QQ 侧新起一轮时回调一次。
 *
 * 只在标签页可见时轮询；同一个 requestId 不重复回调，run 结束后清空去重，
 * 下一轮可以再次触发。非渠道会话（bindingId 为空）不轮询。
 */
export function useChannelSessionWatcher(params: {
  sessionId?: string | null;
  bindingId?: number | null;
  onRunDetected: (requestId: string) => void;
}) {
  const { sessionId, bindingId, onRunDetected } = params;
  const callbackRef = useRef(onRunDetected);
  const lastRunningRequestIdRef = useRef<string | null>(null);

  useEffect(() => {
    callbackRef.current = onRunDetected;
  }, [onRunDetected]);

  useEffect(() => {
    lastRunningRequestIdRef.current = null;
    if (!sessionId || bindingId == null) {
      return;
    }

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const clearTimer = () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    };

    const poll = async () => {
      if (cancelled || document.visibilityState !== "visible") {
        return;
      }
      try {
        const status = await conversationHistoryApi.getSessionStatus(sessionId);
        if (cancelled) {
          return;
        }
        const runningRequestId =
          status?.status === "RUNNING" ? status.latestRequestId : null;
        if (runningRequestId) {
          if (runningRequestId !== lastRunningRequestIdRef.current) {
            lastRunningRequestIdRef.current = runningRequestId;
            callbackRef.current(runningRequestId);
          }
        } else {
          lastRunningRequestIdRef.current = null;
        }
      } catch {
        // 轮询失败不打断对话，下一轮继续。
      }
      if (!cancelled) {
        timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      }
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        clearTimer();
        void poll();
      } else {
        // 隐藏时暂停：不排下一轮，等可见时立即补查一次。
        clearTimer();
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    void poll();

    return () => {
      cancelled = true;
      clearTimer();
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [bindingId, sessionId]);
}
