"use client";

import { cn } from "@/lib/utils";
import { ArrowDownIcon } from "lucide-react";
import type {
  ComponentProps,
  HTMLAttributes,
  MouseEvent as ReactMouseEvent,
  MouseEventHandler,
  ReactNode,
} from "react";
import { useCallback } from "react";
import { StickToBottom, useStickToBottomContext } from "use-stick-to-bottom";

export type ConversationProps = ComponentProps<typeof StickToBottom>;

export const Conversation = ({ className, ...props }: ConversationProps) => (
  <StickToBottom
    className={cn("relative flex-1 overflow-y-hidden", className)}
    initial="instant"
    resize="instant"
    role="log"
    {...props}
  />
);

export type ConversationContentProps = ComponentProps<
  typeof StickToBottom.Content
>;

export const ConversationContent = ({
  className,
  ...props
}: ConversationContentProps) => (
  <StickToBottom.Content
    className={cn("flex flex-col gap-3 px-1 py-2 md:px-2 md:py-3", className)}
    {...props}
  />
);

export type ConversationEmptyStateProps = ComponentProps<"div"> & {
  title?: string;
  description?: string;
  icon?: ReactNode;
};

export const ConversationEmptyState = ({
  className,
  title = "No messages yet",
  description = "Start a conversation to see messages here",
  icon,
  children,
  ...props
}: ConversationEmptyStateProps) => (
  <div
    className={cn(
      "flex size-full flex-col items-center justify-center gap-3 p-8 text-center",
      className
    )}
    {...props}
  >
    {children ?? (
      <>
        {icon && <div className="text-muted-foreground">{icon}</div>}
        <div className="space-y-1">
          <h3 className="font-medium text-sm">{title}</h3>
          {description && (
            <p className="text-muted-foreground text-sm">{description}</p>
          )}
        </div>
      </>
    )}
  </div>
);

export type ConversationScrollButtonProps = HTMLAttributes<HTMLButtonElement> & {
  /** 回到底部按钮文案，可由调用方覆盖以统一语言。 */
  label?: string;
};

export function invokeConversationScrollClick(
  event: ReactMouseEvent<HTMLButtonElement>,
  onClick: MouseEventHandler<HTMLButtonElement> | undefined,
  scrollToBottom: () => void
): void {
  onClick?.(event);
  scrollToBottom();
}

export const ConversationScrollButton = ({
  className,
  label = "有新消息",
  onClick,
  ...props
}: ConversationScrollButtonProps) => {
  const { isAtBottom, scrollToBottom } = useStickToBottomContext();

  const handleScrollToBottom = useCallback(() => {
    scrollToBottom();
  }, [scrollToBottom]);

  if (isAtBottom) {
    return null;
  }

  return (
    <button
      {...props}
      type="button"
      className={cn(
        "kimi-newmsg-pill absolute bottom-4 left-1/2 z-10 -translate-x-1/2",
        className
      )}
      onClick={(event) =>
        invokeConversationScrollClick(event, onClick, handleScrollToBottom)
      }
    >
      <ArrowDownIcon className="size-3.5" />
      {label}
    </button>
  );
};
