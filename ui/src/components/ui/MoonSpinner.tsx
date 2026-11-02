import type { CSSProperties } from "react";
import { cn } from "@/lib/utils";

const MOON_FRAMES = ["🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘"] as const;
const MOON_FRAME_MS = 120;
const MOON_FAST_FRAME_MS = 60;
// 一轮动画 = 每帧停留时长 × 帧数，作为 CSS 变量下发给 global.css，
// 增删帧或调整帧时长时不需要再同步修改样式表里的 animation-duration。
const MOON_DURATION_MS = MOON_FRAMES.length * MOON_FRAME_MS;
const MOON_FAST_DURATION_MS = MOON_FRAMES.length * MOON_FAST_FRAME_MS;

type MoonSpinnerStyle = CSSProperties & {
  "--moon-duration": string;
  "--moon-duration-fast": string;
};

type MoonFrameStyle = CSSProperties & {
  "--moon-frame-delay": string;
  "--moon-frame-fast-delay": string;
};

type MoonSpinnerProps = {
  size?: "sm" | "md" | "lg";
  fast?: boolean;
  label: string;
  className?: string;
};

export function MoonSpinner({
  size = "md",
  fast = false,
  label,
  className,
}: MoonSpinnerProps) {
  return (
    <span
      className={cn(
        "ui-moon",
        `ui-moon--${size}`,
        fast && "ui-moon--fast",
        className
      )}
      style={
        {
          "--moon-duration": `${MOON_DURATION_MS}ms`,
          "--moon-duration-fast": `${MOON_FAST_DURATION_MS}ms`,
        } as MoonSpinnerStyle
      }
      aria-label={label}
      role="img"
    >
      {MOON_FRAMES.map((frame, index) => (
        <span
          key={frame}
          className="ui-moon__frame"
          style={
            {
              "--moon-frame-delay": `${index * MOON_FRAME_MS}ms`,
              "--moon-frame-fast-delay": `${index * MOON_FAST_FRAME_MS}ms`,
            } as MoonFrameStyle
          }
          aria-hidden
        >
          {frame}
        </span>
      ))}
    </span>
  );
}
