import { useMemo } from "react";
import { useReducedMotion, type Transition } from "motion/react";

/** Strong ease-out for UI enters (AUDIT --ease-out). */
export const EASE_OUT = [0.23, 1, 0.32, 1] as const;

/** Strong ease-in-out for on-screen movement (AUDIT --ease-in-out). */
export const EASE_IN_OUT = [0.77, 0, 0.175, 1] as const;

export const DURATION = {
  press: 0.15,
  tooltip: 0.15,
  dropdown: 0.2,
  panel: 0.25,
  modal: 0.3,
  message: 0.22,
  reduced: 0.12,
} as const;

/**
 * 纯透明度/旋转类过渡的完整 transition 预设。
 *
 * 这类变化没有位移，弹簧回弹无从体现，所以用缓动而不是 spring；
 * 但时长与曲线仍必须从这里取，不允许组件里散写 `duration: 0.18`。
 */
export const DURATION_QUICK: Transition = {
  duration: 0.18,
  ease: EASE_OUT,
};

export const DURATION_MODAL: Transition = {
  duration: DURATION.modal,
  ease: EASE_OUT,
};

/** 呼吸脉冲：流式进行中的无限循环节奏（reduced 时由调用方改用短时长或关闭）。 */
export const PULSE_LOOP: Transition = {
  duration: 2,
  repeat: Number.POSITIVE_INFINITY,
  ease: "easeInOut",
};

/**
 * 弹簧预设 —— Apple-grade 动效的唯一来源。
 *
 * 物理感来自弹簧而不是 cubic-bezier：真正的位移/高亮/悬浮反馈必须用这三档，
 * 纯淡入淡色等不可中断的短过渡才继续用 `DURATION` + `EASE_OUT`。
 *
 * 参数取向（低阻尼 → 跟手、有轻微回弹；高阻尼 → 稳、不晃）：
 * - `SPRING_SOFT`   侧栏高亮、tab 切换等「跟随选择」的块位移，回弹最明显
 * - `SPRING_SNAPPY` 卡片浮起、hover/按压反馈，短促不拖沓
 * - `SPRING_GENTLE` 面板展开、欢迎页主视觉，行程大所以要更稳
 */
export const SPRING_SOFT: Transition = {
  type: "spring",
  stiffness: 320,
  damping: 30,
  mass: 0.9,
};

export const SPRING_SNAPPY: Transition = {
  type: "spring",
  stiffness: 480,
  damping: 32,
  mass: 0.6,
};

export const SPRING_GENTLE: Transition = {
  type: "spring",
  stiffness: 210,
  damping: 26,
  mass: 1,
};

/** reduced-motion 下的弹簧替身：保留状态变化，去掉位移感。 */
export const REDUCED_SPRING: Transition = {
  duration: DURATION.reduced,
  ease: EASE_OUT,
};

/**
 * 按 reduce 取弹簧；reduced 时退化为短淡入，不做位移。
 * 组件里一律通过这个工厂取 transition，不要直接引用 SPRING_*。
 */
export const useSpring = (
  spring: Transition = SPRING_SOFT
): Transition => {
  const reduce = !!useReducedMotion();
  return reduce ? REDUCED_SPRING : spring;
};

/** Shared motion config for high-traffic surfaces. */
const MOTION_FADE = {
  initial: {
    opacity: 0,
    y: 8,
  },
  animate: {
    opacity: 1,
    y: 0,
  },
  exit: {
    opacity: 0,
    y: -6,
  },
} as const;

const REDUCED_MOTION_FADE = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
} as const;

export function useMotionConfig() {
  const reduce = !!useReducedMotion();
  // 尊重系统减弱动态设置：缩短过渡并关闭无限循环，避免仅隐藏动画仍持续占用资源。
  // 按 reduce 缓存整个返回值，保证 initial/animate/exit 的引用跨渲染稳定。
  return useMemo(
    () => ({
      reduce,
      duration: reduce ? DURATION.reduced : DURATION.message,
      ease: EASE_OUT,
      fade: reduce ? REDUCED_MOTION_FADE : MOTION_FADE,
      /** When reduce is true, do not pass this as transition.repeat. */
      loop: reduce ? undefined : Number.POSITIVE_INFINITY,
    }),
    [reduce]
  );
}

/**
 * 深搜预览卡片的进场过渡预设。
 * 供 Timeline 中 DeepSearchPreviewItem 使用。
 */
export const DEEP_SEARCH_PREVIEW_ENTER: Transition = {
  duration: 0.2,
  ease: [0.25, 0.46, 0.45, 0.94],
};

/**
 * 排队摘要滚动切换的过渡预设。
 * 供 QueuedSummaryContent 使用。
 */
export const SUMMARY_ROLL_TRANSITION: Transition = {
  duration: 0.3,
  ease: [0.4, 0, 0.2, 1],
};

/**
 * 指标数值翻转的过渡预设。
 * 供 FlipMetricValue 使用。
 */
export const FLIP_TRANSITION: Transition = {
  duration: 0.16,
  ease: [0.4, 0, 0.2, 1],
};
