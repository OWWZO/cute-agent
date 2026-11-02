import { useCallback, useEffect, useRef } from "react";

/** 时间线里可能出现的滚动容器（与 `use-stick-to-bottom` 的容器约定一致）。 */
const SCROLLER_SELECTOR =
  "[data-stick-to-bottom], .overflow-y-auto, .overflow-auto";

/**
 * 展开 / 收起后把触发元素「钉」在视口原位。
 *
 * 折叠区高度变化会把下方行整体推移；头部一旦不在可视区顶端，用户视线就会跟着跳。
 * 做法是记录切换前后头部相对视口的位移，再反方向补回最近的滚动容器。
 * `ToolRow` 与 `ToolGroup` 共用这一套启发式，避免两处各维护一份选择器列表。
 */
export function usePinScrollOnToggle<T extends HTMLElement>() {
  const targetRef = useRef<T | null>(null);
  const frameRef = useRef<number | null>(null);

  const pinScroll = useCallback(() => {
    const el = targetRef.current;
    if (!el) return;
    if (frameRef.current !== null) {
      cancelAnimationFrame(frameRef.current);
    }
    const top = el.getBoundingClientRect().top;
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null;
      // 快速切换会话/卸载后节点已脱离文档，此时 rect 全是 0，继续补偿会把外层滚动拉偏。
      if (!el.isConnected) return;
      const next = el.getBoundingClientRect().top;
      const delta = next - top;
      if (Math.abs(delta) > 1) {
        const scroller = el.closest(SCROLLER_SELECTOR);
        if (scroller instanceof HTMLElement) {
          scroller.scrollTop += delta;
        } else {
          window.scrollBy(0, delta);
        }
      }
    });
  }, []);

  useEffect(() => {
    return () => {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current);
      }
    };
  }, []);

  return [targetRef, pinScroll] as const;
}
