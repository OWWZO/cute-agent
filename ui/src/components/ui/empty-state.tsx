import type { LucideIcon } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";

import { cn } from "@/lib/utils";

/** 同一 variant 的所有尺寸/间距集中在这里，新增变体只改此表 */
const EMPTY_STATE_VARIANTS = {
  default: {
    root: "px-4 py-12",
    icon: "mb-4 h-12 w-12",
    iconSize: "h-6 w-6",
    title: "text-[length:var(--content-font-size)]",
    description: "text-ui-caption",
    action: "mt-4",
  },
  compact: {
    root: "px-4 py-6",
    icon: "mb-3 h-9 w-9",
    iconSize: "h-[18px] w-[18px]",
    title: "text-ui-base",
    description: "text-ui-sm",
    action: "mt-3",
  },
} as const;

export interface EmptyStateProps extends ComponentProps<"div"> {
  icon?: LucideIcon;
  title?: string;
  description?: string;
  action?: ReactNode;
  variant?: keyof typeof EMPTY_STATE_VARIANTS;
  /** 标题标签，默认 h3；调用方可按页面标题层级覆盖 */
  titleAs?: "h1" | "h2" | "h3" | "h4";
}

function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  variant = "default",
  titleAs: TitleTag = "h3",
  className,
  ...props
}: EmptyStateProps) {
  const styles = EMPTY_STATE_VARIANTS[variant];

  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center text-center",
        styles.root,
        className
      )}
      {...props}
    >
      {Icon && (
        <div
          className={cn(
            "flex items-center justify-center rounded-xl bg-[var(--primary)]/10 text-[var(--primary)]",
            styles.icon
          )}
        >
          <Icon className={styles.iconSize} />
        </div>
      )}
      {title && (
        <TitleTag
          className={cn(
            "font-semibold text-[var(--chat-text)]",
            styles.title
          )}
        >
          {title}
        </TitleTag>
      )}
      {description && (
        <p
          className={cn(
            "mt-1 text-[var(--chat-text-muted)]",
            styles.description
          )}
        >
          {description}
        </p>
      )}
      {action && <div className={styles.action}>{action}</div>}
    </div>
  );
}

export { EmptyState };
