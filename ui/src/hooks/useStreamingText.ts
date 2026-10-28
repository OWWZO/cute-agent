import { useEffect, useRef, useState } from "react";

const STREAM_TEXT_FRAME_INTERVAL_MS = 28;
const STREAM_TEXT_MAX_CHUNK = 24;
const STREAM_TEXT_BACKLOG_SNAP_CHARS = 2000;

/**
 * 每帧追字步长：积压越多走得越快，上限 24，避免一帧刷出整段。
 * 下限 1，保证正常节奏下仍是逐字追赶。
 */
export function resolveStreamingChunkSize(readyChars: number): number {
  return Math.min(STREAM_TEXT_MAX_CHUNK, Math.max(1, Math.ceil(readyChars / 6)));
}

/**
 * 与终答 MessageResponse 同款：把后端累计文本缓成前端逐字/小步追赶，
 * 避免 SSE 节流导致「整段蹦字」。
 */
export function useStreamingText(text: string, isStreaming: boolean): string {
  const [displayedText, setDisplayedText] = useState(text);
  const displayedRef = useRef(text);
  const targetRef = useRef(text);
  const frameRef = useRef<number | null>(null);
  const lastUpdateRef = useRef(0);
  const lastTargetChangeRef = useRef(0);

  useEffect(() => {
    targetRef.current = text;
    lastTargetChangeRef.current = performance.now();

    if (!isStreaming) {
      displayedRef.current = text;
      setDisplayedText(text);
      if (frameRef.current) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
      return;
    }

    if (displayedRef.current.length > text.length) {
      displayedRef.current = text;
      setDisplayedText(text);
    }

    // 前缀不一致（重连/换卡）时直接对齐，避免乱序拼接
    if (!text.startsWith(displayedRef.current) && displayedRef.current.length > 0) {
      const common = commonPrefixLength(displayedRef.current, text);
      displayedRef.current = text.slice(0, common);
      setDisplayedText(displayedRef.current);
    }

    const tick = (timestamp: number) => {
      const frameInterval = STREAM_TEXT_FRAME_INTERVAL_MS;
      if (timestamp - lastUpdateRef.current < frameInterval) {
        frameRef.current = requestAnimationFrame(tick);
        return;
      }
      lastUpdateRef.current = timestamp;

      const currentValue = displayedRef.current;
      const targetValue = targetRef.current;

      if (currentValue === targetValue) {
        frameRef.current = null;
        return;
      }

      if (!targetValue.startsWith(currentValue)) {
        const common = commonPrefixLength(currentValue, targetValue);
        displayedRef.current = targetValue.slice(0, Math.max(common, 0));
        setDisplayedText(displayedRef.current);
        frameRef.current = requestAnimationFrame(tick);
        return;
      }

      const remaining = targetValue.length - currentValue.length;
      // 积压远超一帧可追的量（例如标签页恢复 / 后端突发），逐帧追赶会让文字长时间
      // 落后于真实输出，这里直接对齐。
      if (remaining > STREAM_TEXT_BACKLOG_SNAP_CHARS) {
        displayedRef.current = targetValue;
        setDisplayedText(targetValue);
        frameRef.current = requestAnimationFrame(tick);
        return;
      }

      const keepBuffer =
        remaining > 6 && timestamp - lastTargetChangeRef.current < 180
          ? Math.min(24, Math.max(4, Math.floor(remaining * 0.35)))
          : 0;
      const readyChars = Math.max(1, remaining - keepBuffer);
      // 入参/终答同节奏：小步追赶，积压多时按比例提速
      const chunkSize = resolveStreamingChunkSize(readyChars);
      const nextValue = targetValue.slice(0, currentValue.length + chunkSize);

      displayedRef.current = nextValue;
      setDisplayedText(nextValue);
      frameRef.current = requestAnimationFrame(tick);
    };

    if (!frameRef.current) {
      frameRef.current = requestAnimationFrame(tick);
    }

    return () => {
      if (frameRef.current) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
    };
  }, [isStreaming, text]);

  return displayedText;
}

function commonPrefixLength(a: string, b: string): number {
  const n = Math.min(a.length, b.length);
  let i = 0;
  while (i < n && a.charCodeAt(i) === b.charCodeAt(i)) {
    i += 1;
  }
  return i;
}
