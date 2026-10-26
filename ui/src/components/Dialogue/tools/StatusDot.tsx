import { useEffect, useRef } from "react";
import { motion } from "motion/react";
import { cn } from "@/lib/utils";
import { SPRING_SNAPPY, useSpring } from "@/lib/motion";

type DotKind = "ok" | "error" | "running" | "suspended" | "idle";

function normalize(status?: string): DotKind {
  switch (status) {
    case "ok":
    case "done":
    case "completed":
    case "success":
      return "ok";
    case "error":
    case "failed":
    case "danger":
      return "error";
    case "running":
    case "working":
    case "in_progress":
    case "active":
      return "running";
    case "suspended":
      return "suspended";
    default:
      return "idle";
  }
}

export function StatusDot({ status }: { status?: string }) {
  const kind = normalize(status);
  // 完成态 settle：只在「非完成 → 完成」的迁移瞬间播一次微缩放回弹；
  // 历史回放直接以完成态挂载时 prevKindRef 初值就是 ok，不会误播。
  const prevKindRef = useRef<DotKind>(kind);
  const justSettled = prevKindRef.current !== "ok" && kind === "ok";
  useEffect(() => {
    prevKindRef.current = kind;
  }, [kind]);
  const settleTransition = useSpring(SPRING_SNAPPY);

  return (
    <motion.span
      className={cn("kw-dot", "kw-dot--" + kind)}
      aria-hidden
      initial={justSettled ? {
        scale: 0.5,
        opacity: 0.6
      } : false}
      animate={{
        scale: 1,
        opacity: 1
      }}
      transition={settleTransition}
    />
  );
}
