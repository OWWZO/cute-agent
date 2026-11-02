import { CheckCircle2, Circle, Loader2 } from "lucide-react";
import type { ReactNode } from "react";

const iconWrap = "flex h-8 w-8 shrink-0 items-center justify-center";

const wrap = (icon: ReactNode) => (
  <div className={iconWrap} aria-hidden>
    {icon}
  </div>
);

export const getStatusIcon = (status?: CHAT.PlanStatus) => {
  // 计划状态只影响图标和动效，未知状态保留固定尺寸占位以避免列表跳动。
  switch (status) {
    case "not_started":
      return wrap(
        <Circle
          className="h-4 w-4 text-[var(--chat-text-muted)] opacity-55"
          strokeWidth={1.75}
        />
      );
    case "in_progress":
      return wrap(
        <Loader2
          className="h-4 w-4 animate-spin text-[var(--chat-accent)]"
          strokeWidth={2}
        />
      );
    case "completed":
      return wrap(
        <CheckCircle2
          className="h-4 w-4 text-[var(--success)]"
          strokeWidth={2}
        />
      );
    default:
      return <div className={iconWrap} />;
  }
};
