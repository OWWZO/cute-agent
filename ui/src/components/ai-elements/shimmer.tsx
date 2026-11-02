"use client";

import { cn } from "@/lib/utils";
import { motion, useReducedMotion } from "motion/react";
import {
  type CSSProperties,
  type JSX,
  memo,
  useMemo,
} from "react";

export type TextShimmerProps = {
  children: string;
  as?: keyof JSX.IntrinsicElements;
  className?: string;
  duration?: number;
  spread?: number;
};

const ShimmerComponent = ({
  children,
  as: Component = "span",
  className,
  duration = 2,
  spread = 2,
}: TextShimmerProps) => {
  const shouldReduceMotion = useReducedMotion();
  const MotionComponent = useMemo(() => motion.create(Component), [Component]);

  const dynamicSpread = useMemo(
    () => (children?.length ?? 0) * spread,
    [children, spread]
  );

  const bgSize = `${Math.max(240, dynamicSpread + 220)}% 100%`;

  return (
    <MotionComponent
      animate={
        shouldReduceMotion
          ? undefined
          : { backgroundPosition: ["220% center", "-220% center"] }
      }
      className={cn("relative inline-block align-baseline", className)}
      initial={{ backgroundPosition: "220% center" }}
      style={
        {
          backgroundImage:
            "linear-gradient(90deg, var(--color-muted-foreground) 0%, var(--color-muted-foreground) 36%, var(--chat-text) 49.5%, var(--chat-text) 50.5%, var(--color-muted-foreground) 64%, var(--color-muted-foreground) 100%)",
          backgroundSize: bgSize,
          backgroundRepeat: "no-repeat",
          WebkitBackgroundClip: "text",
          backgroundClip: "text",
          color: "transparent",
          willChange: shouldReduceMotion ? undefined : "background-position",
        } as CSSProperties
      }
      transition={
        shouldReduceMotion
          ? undefined
          : {
            repeat: Number.POSITIVE_INFINITY,
            duration,
            ease: "linear",
            repeatType: "loop",
          }
      }
    >
      {children}
    </MotionComponent>
  );
};

export const Shimmer = memo(ShimmerComponent);
