"use client";

import { useControllableState } from "@radix-ui/react-use-controllable-state";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { cn } from "@/lib/utils";
import { ChevronDownIcon } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { createContext, memo, useContext, useEffect, useRef, useState } from "react";
import { motion } from "motion/react";
import { DURATION_QUICK, DURATION_MODAL } from "@/lib/motion";
import { MessageResponse } from "./message";
import { normalizeThinkingText } from "@/utils/markdown";

type ReasoningContextValue = {
  isStreaming: boolean;
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
  duration: number | undefined;
};

const ReasoningContext = createContext<ReasoningContextValue | null>(null);

export const useReasoning = () => {
  const context = useContext(ReasoningContext);
  if (!context) {
    throw new Error("Reasoning components must be used within Reasoning");
  }
  return context;
};

export type ReasoningProps = ComponentProps<typeof Collapsible> & {
  isStreaming?: boolean;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  duration?: number;
};

const MS_IN_S = 1000;

export const Reasoning = memo(
  ({
    className,
    isStreaming = false,
    open,
    defaultOpen = true,
    onOpenChange,
    duration: durationProp,
    children,
    ...props
  }: ReasoningProps) => {
    const [isOpen, setIsOpen] = useControllableState({
      prop: open,
      defaultProp: defaultOpen,
      onChange: onOpenChange,
    });
    const [duration, setDuration] = useControllableState({
      prop: durationProp,
      defaultProp: undefined,
    });

    const [startTime, setStartTime] = useState<number | null>(null);
    const wasStreaming = useRef(isStreaming);

    // Track duration when streaming starts and ends
    useEffect(() => {
      if (isStreaming) {
        if (startTime === null) {
          setStartTime(Date.now());
        }
      } else if (startTime !== null) {
        setDuration(Math.ceil((Date.now() - startTime) / MS_IN_S));
        setStartTime(null);
      }
    }, [isStreaming, startTime, setDuration]);

    useEffect(() => {
      if (isStreaming && !wasStreaming.current) {
        setIsOpen(true);
      } else if (!isStreaming && wasStreaming.current) {
        setIsOpen(false);
      }
      wasStreaming.current = isStreaming;
    }, [isStreaming, setIsOpen]);

    const handleOpenChange = (newOpen: boolean) => {
      setIsOpen(newOpen);
    };

    return (
      <ReasoningContext.Provider
        value={{ isStreaming, isOpen, setIsOpen, duration }}
      >
        <motion.div
          initial={{ opacity: 1, y: 3 }}
          animate={{ opacity: 1, y: 0 }}
          transition={DURATION_QUICK}
        >
          <Collapsible
            className={cn("not-prose mb-4", className)}
            onOpenChange={handleOpenChange}
            open={isOpen}
            {...props}
          >
            {children}
          </Collapsible>
        </motion.div>
      </ReasoningContext.Provider>
    );
  }
);

export type ReasoningTriggerProps = ComponentProps<typeof CollapsibleTrigger> & {
  getThinkingMessage?: (isStreaming: boolean, duration?: number) => ReactNode;
};

// 动态思考指示器：与步骤执行共用 thinking-shimmer 节奏
const ThinkingIndicator = memo(() => (
  <span className="thinking-shimmer text-ui-caption font-medium tracking-[0.02em]">
    正在理解问题…
  </span>
));

ThinkingIndicator.displayName = "ThinkingIndicator";

const defaultGetThinkingMessage = (isStreaming: boolean, duration?: number) => {
  if (isStreaming) {
    return <ThinkingIndicator />;
  }
  if (duration === undefined || duration === 0) {
    return <span className="text-ui-caption text-muted-foreground/80">思考完成</span>;
  }
  return <span className="text-ui-caption text-muted-foreground/80">已思考 {duration} 秒</span>;
};

export const ReasoningTrigger = memo(
  ({ className, children, getThinkingMessage = defaultGetThinkingMessage, ...props }: ReasoningTriggerProps) => {
    const { isStreaming, isOpen, duration } = useReasoning();

    return (
      <CollapsibleTrigger
        className={cn(
          "flex w-full items-center gap-2 rounded-md p-1.5 -m-1.5 text-ui-caption text-muted-foreground/88 transition-colors hover:bg-muted/25 hover:text-foreground/82",
          className
        )}
        {...props}
      >
        {children ?? (
          <>
            <div className="min-w-0 flex-1 text-left">
              {getThinkingMessage(isStreaming, duration)}
            </div>
            <motion.div
              animate={{ rotate: isOpen ? 180 : 0 }}
              transition={DURATION_MODAL}
            >
              <ChevronDownIcon className="size-4" />
            </motion.div>
          </>
        )}
      </CollapsibleTrigger>
    );
  }
);

export type ReasoningContentProps = ComponentProps<
  typeof CollapsibleContent
> & {
  children: string;
};

export const ReasoningContent = memo(
  ({ className, children, ...props }: ReasoningContentProps) => {
    const { isStreaming } = useReasoning();
    const normalizedChildren = normalizeThinkingText(children);

    return (
      <CollapsibleContent
        className={cn(
          "overflow-hidden",
          className
        )}
        {...props}
      >
        <div className="mt-2.5">
          <MessageResponse
            isStreaming={isStreaming}
            animateByChars={false}
            showStreamingCursor={false}
            className="text-ui-caption leading-6 tracking-[-0.01em] text-foreground/68 [&_p]:text-ui-caption [&_p]:leading-6 [&_li]:text-ui-caption [&_li]:leading-6 [&_ol]:leading-6 [&_ul]:leading-6 [&_h1]:text-ui-base [&_h2]:text-ui-caption [&_h3]:text-ui-caption [&_blockquote]:text-foreground/56 [&_code]:text-ui-sm"
          >
            {normalizedChildren}
          </MessageResponse>
        </div>
      </CollapsibleContent>
    );
  }
);

Reasoning.displayName = "Reasoning";
ReasoningTrigger.displayName = "ReasoningTrigger";
ReasoningContent.displayName = "ReasoningContent";
