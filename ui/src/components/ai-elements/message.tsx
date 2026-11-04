"use client";

import { Button } from "@/components/ui/button";
import {
  ButtonGroup,
  ButtonGroupText,
} from "@/components/ui/button-group";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import type { FileUIPart, UIMessage } from "ai";
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  PaperclipIcon,
  XIcon,
} from "lucide-react";
import type { ComponentProps, HTMLAttributes, ReactElement } from "react";
import { createContext, memo, useContext, useEffect, useRef, useState } from "react";
import { Streamdown } from "streamdown";
import { motion, useSpring, useMotionValue } from "motion/react";

export type MessageProps = HTMLAttributes<HTMLDivElement> & {
  from: UIMessage["role"];
};

export const Message = ({ className, from, ...props }: MessageProps) => (
  <div
    className={cn(
      "group flex w-full max-w-full flex-col gap-2",
      from === "user" ? "is-user ml-auto items-end justify-end" : "is-assistant items-start",
      className
    )}
    {...props}
  />
);

export type MessageContentProps = HTMLAttributes<HTMLDivElement>;

export const MessageContent = ({
  children,
  className,
  ...props
}: MessageContentProps) => (
  <div
    className={cn(
      "is-user:dark flex w-fit max-w-full min-w-0 flex-col gap-2 overflow-hidden text-[length:var(--content-font-size)] leading-[1.75]",
      // 用户气泡：ClawsGO 风格灰底胶囊
      "group-[.is-user]:chat-user-bubble",
      "group-[.is-assistant]:w-full group-[.is-assistant]:rounded-none group-[.is-assistant]:border-0 group-[.is-assistant]:bg-transparent group-[.is-assistant]:px-0 group-[.is-assistant]:py-0 group-[.is-assistant]:text-[var(--chat-text)] group-[.is-assistant]:shadow-none",
      className
    )}
    {...props}
  >
    {children}
  </div>
);

export type MessageActionsProps = ComponentProps<"div">;

export const MessageActions = ({
  className,
  children,
  ...props
}: MessageActionsProps) => (
  <div
    className={cn(
      "flex items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100",
      className
    )}
    {...props}
  >
    {children}
  </div>
);

export type MessageActionProps = ComponentProps<typeof Button> & {
  tooltip?: string;
  label?: string;
};

export const MessageAction = ({
  tooltip,
  children,
  label,
  variant = "ghost",
  size = "icon-sm",
  ...props
}: MessageActionProps) => {
  const button = (
    <Button size={size} type="button" variant={variant} {...props}>
      {children}
      <span className="sr-only">{label || tooltip}</span>
    </Button>
  );

  if (tooltip) {
    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>{button}</TooltipTrigger>
          <TooltipContent>
            <p>{tooltip}</p>
          </TooltipContent>
        </Tooltip>
      </TooltipProvider>
    );
  }

  return button;
};

type MessageBranchContextType = {
  currentBranch: number;
  totalBranches: number;
  goToPrevious: () => void;
  goToNext: () => void;
  branches: ReactElement[];
  setBranches: (branches: ReactElement[]) => void;
};

const MessageBranchContext = createContext<MessageBranchContextType | null>(
  null
);

const useMessageBranch = () => {
  const context = useContext(MessageBranchContext);

  if (!context) {
    throw new Error(
      "MessageBranch components must be used within MessageBranch"
    );
  }

  return context;
};

export type MessageBranchProps = HTMLAttributes<HTMLDivElement> & {
  defaultBranch?: number;
  onBranchChange?: (branchIndex: number) => void;
};

export const MessageBranch = ({
  defaultBranch = 0,
  onBranchChange,
  className,
  ...props
}: MessageBranchProps) => {
  const [currentBranch, setCurrentBranch] = useState(defaultBranch);
  const [branches, setBranches] = useState<ReactElement[]>([]);

  const handleBranchChange = (newBranch: number) => {
    setCurrentBranch(newBranch);
    onBranchChange?.(newBranch);
  };

  const goToPrevious = () => {
    const newBranch =
      currentBranch > 0 ? currentBranch - 1 : branches.length - 1;
    handleBranchChange(newBranch);
  };

  const goToNext = () => {
    const newBranch =
      currentBranch < branches.length - 1 ? currentBranch + 1 : 0;
    handleBranchChange(newBranch);
  };

  const contextValue: MessageBranchContextType = {
    currentBranch,
    totalBranches: branches.length,
    goToPrevious,
    goToNext,
    branches,
    setBranches,
  };

  return (
    <MessageBranchContext.Provider value={contextValue}>
      <div
        className={cn("grid w-full gap-2 [&>div]:pb-0", className)}
        {...props}
      />
    </MessageBranchContext.Provider>
  );
};

export type MessageBranchContentProps = HTMLAttributes<HTMLDivElement>;

export const MessageBranchContent = ({
  children,
  ...props
}: MessageBranchContentProps) => {
  const { currentBranch, setBranches, branches } = useMessageBranch();
  const childrenArray = Array.isArray(children) ? children : [children];

  // Use useEffect to update branches when they change
  useEffect(() => {
    if (branches.length !== childrenArray.length) {
      setBranches(childrenArray);
    }
  }, [childrenArray, branches, setBranches]);

  return childrenArray.map((branch, index) => (
    <div
      className={cn(
        "grid gap-2 overflow-hidden [&>div]:pb-0",
        index === currentBranch ? "block" : "hidden"
      )}
      key={branch.key}
      {...props}
    >
      {branch}
    </div>
  ));
};

export type MessageBranchSelectorProps = HTMLAttributes<HTMLDivElement> & {
  from: UIMessage["role"];
};

export const MessageBranchSelector = ({
  className,
  from: _from,
  ...props
}: MessageBranchSelectorProps) => {
  const { totalBranches } = useMessageBranch();
  void _from;

  // Don't render if there's only one branch
  if (totalBranches <= 1) {
    return null;
  }

  return (
    <ButtonGroup
      className={cn(
        "[&>*:not(:first-child)]:rounded-l-md [&>*:not(:last-child)]:rounded-r-md",
        className
      )}
      orientation="horizontal"
      {...props}
    />
  );
};

export type MessageBranchPreviousProps = ComponentProps<typeof Button>;

export const MessageBranchPrevious = ({
  children,
  ...props
}: MessageBranchPreviousProps) => {
  const { goToPrevious, totalBranches } = useMessageBranch();

  return (
    <Button
      aria-label="Previous branch"
      disabled={totalBranches <= 1}
      onClick={goToPrevious}
      size="icon-sm"
      type="button"
      variant="ghost"
      {...props}
    >
      {children ?? <ChevronLeftIcon size={14} />}
    </Button>
  );
};

export type MessageBranchNextProps = ComponentProps<typeof Button>;

export const MessageBranchNext = ({
  children,
  className,
  ...props
}: MessageBranchNextProps) => {
  const { goToNext, totalBranches } = useMessageBranch();

  return (
    <Button
      aria-label="Next branch"
      className={className}
      disabled={totalBranches <= 1}
      onClick={goToNext}
      size="icon-sm"
      type="button"
      variant="ghost"
      {...props}
    >
      {children ?? <ChevronRightIcon size={14} />}
    </Button>
  );
};

export type MessageBranchPageProps = HTMLAttributes<HTMLSpanElement>;

export const MessageBranchPage = ({
  className,
  ...props
}: MessageBranchPageProps) => {
  const { currentBranch, totalBranches } = useMessageBranch();

  return (
    <ButtonGroupText
      className={cn(
        "border-none bg-transparent text-muted-foreground shadow-none",
        className
      )}
      {...props}
    >
      {currentBranch + 1} of {totalBranches}
    </ButtonGroupText>
  );
};

// ... existing code ...

export type MessageResponseProps = Omit<
  ComponentProps<typeof Streamdown>,
  "children"
> & {
  children?: string | number;
  isStreaming?: boolean;
  animateByChars?: boolean;
  showStreamingCursor?: boolean;
  disableAutoScroll?: boolean;
  /** 显式指定滚动容器；缺省时向上查找最近的可滚动祖先。 */
  scrollContainer?: HTMLElement | null;
};

const toSourceText = (children: MessageResponseProps["children"]): string => {
  if (typeof children === "string") {
    return children;
  }
  if (typeof children === "number") {
    return String(children);
  }
  return "";
};

const useStreamingText = (text: string, isStreaming: boolean) => {
  const [displayedText, setDisplayedText] = useState(text);
  const displayedRef = useRef(text);
  const targetRef = useRef(text);
  const frameRef = useRef<number | null>(null);
  const lastUpdateRef = useRef<number>(0);
  const lastTargetChangeRef = useRef<number>(0);

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

    const tick = (timestamp: number) => {
      // 将流式节奏放慢一些，给后端抖动留出前端缓冲，减少“一下快一下停”的卡顿感。
      const frameInterval = 32;
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

      const remaining = targetValue.length - currentValue.length;
      const keepBuffer =
        remaining > 6 && timestamp - lastTargetChangeRef.current < 180
          ? Math.min(18, Math.max(4, Math.floor(remaining * 0.35)))
          : 0;
      const readyChars = Math.max(1, remaining - keepBuffer);
      // 小步消费增量文本，避免前端瞬间追平后又因后端延迟出现明显停顿。
      const chunkSize =
        readyChars > 24 ? 3 : readyChars > 8 ? 2 : 1;
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
};

const SCROLL_THRESHOLD = 80;
const SCROLL_THROTTLE_MS = 80;

/** 滚动容器不写死层级：向上找到第一个可滚动祖先，兼容外层包裹结构变化。 */
const findScrollableAncestor = (element: HTMLElement): HTMLElement | null => {
  let node = element.parentElement;
  while (node) {
    const { overflowY } = getComputedStyle(node);
    if (overflowY === "auto" || overflowY === "scroll") {
      return node;
    }
    node = node.parentElement;
  }
  return null;
};

const useNearBottomAutoScroll = (
  enabled: boolean,
  trigger: string,
  scrollContainer?: HTMLElement | null
) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const lastScrollRef = useRef(0);
  const scrollFrameRef = useRef<number | null>(null);
  const scrollTimerRef = useRef<number | null>(null);
  const scrollTargetRef = useRef<HTMLElement | null>(null);
  const wasEnabledRef = useRef(false);

  useEffect(() => {
    const clearPending = () => {
      if (scrollFrameRef.current !== null) {
        cancelAnimationFrame(scrollFrameRef.current);
        scrollFrameRef.current = null;
      }
      if (scrollTimerRef.current !== null) {
        window.clearTimeout(scrollTimerRef.current);
        scrollTimerRef.current = null;
      }
    };

    clearPending();

    // 流式刚结束时补一次滚动，避免最后一帧内容停在视口下方。
    const justStoppedStreaming = wasEnabledRef.current && !enabled;
    wasEnabledRef.current = enabled;
    if ((!enabled && !justStoppedStreaming) || !containerRef.current) {
      return clearPending;
    }

    const target =
      scrollContainer ??
      (scrollTargetRef.current ??= findScrollableAncestor(containerRef.current));
    if (!target) return clearPending;

    const scrollIfNearBottom = () => {
      const distance =
        target.scrollHeight - target.scrollTop - target.clientHeight;
      if (distance < SCROLL_THRESHOLD) {
        target.scrollTop = target.scrollHeight;
      }
    };

    if (!enabled) {
      scrollIfNearBottom();
      return clearPending;
    }

    const scrollInFrame = () => {
      scrollFrameRef.current = requestAnimationFrame(() => {
        scrollFrameRef.current = null;
        lastScrollRef.current = Date.now();
        scrollIfNearBottom();
      });
    };

    const elapsed = Date.now() - lastScrollRef.current;
    if (elapsed < SCROLL_THROTTLE_MS) {
      // 节流窗口内改为尾随触发，避免直接丢弃最后一次内容更新。
      scrollTimerRef.current = window.setTimeout(
        scrollInFrame,
        SCROLL_THROTTLE_MS - elapsed
      );
    } else {
      scrollInFrame();
    }

    return clearPending;
  }, [enabled, trigger, scrollContainer]);

  return containerRef;
};

// 流式光标组件
const StreamingCursor = memo(() => {
  const opacity = useMotionValue(1);
  const smoothOpacity = useSpring(opacity, {
    stiffness: 300,
    damping: 30,
  });

  useEffect(() => {
    const interval = setInterval(() => {
      opacity.set(opacity.get() === 1 ? 0.3 : 1);
    }, 530);

    return () => clearInterval(interval);
  }, [opacity]);

  return (
    <motion.span
      aria-hidden
      className="ai-stream-cursor"
      style={{ opacity: smoothOpacity }}
    />
  );
});

StreamingCursor.displayName = "StreamingCursor";

export const MessageResponse = memo(
  ({
    className,
    isStreaming = false,
    animateByChars = true,
    showStreamingCursor = true,
    disableAutoScroll = false,
    scrollContainer,
    children,
    ...props
  }: MessageResponseProps) => {
    const sourceText = toSourceText(children);
    // Performance: even when `animateByChars=false` (e.g. reasoning content),
    // still throttle updates during streaming so markdown doesn't re-render on every token.
    const shouldStreamThrottle = isStreaming;
    const animatedText = useStreamingText(sourceText, shouldStreamThrottle);
    const renderedText = animateByChars ? animatedText : shouldStreamThrottle ? animatedText : sourceText;
    const containerRef = useNearBottomAutoScroll(
      isStreaming && !disableAutoScroll,
      renderedText,
      scrollContainer
    );

    return (
      <div
        ref={containerRef}
        className="size-full"
      >
        <Streamdown
          className={cn(
            "ai-chat-markdown size-full [&>*:first-child]:mt-0 [&>*:last-child]:mb-0",
            className
          )}
          {...props}
        >
          {renderedText}
        </Streamdown>
        {isStreaming && showStreamingCursor && <StreamingCursor />}
      </div>
    );
  },
  (prevProps, nextProps) =>
    prevProps.children === nextProps.children &&
    prevProps.isStreaming === nextProps.isStreaming &&
    prevProps.animateByChars === nextProps.animateByChars &&
    prevProps.showStreamingCursor === nextProps.showStreamingCursor &&
    prevProps.disableAutoScroll === nextProps.disableAutoScroll &&
    prevProps.scrollContainer === nextProps.scrollContainer &&
    // 终答产物表解析会换 components（img/a），必须参与比较，否则流式中途解析不到文件。
    prevProps.components === nextProps.components
);

MessageResponse.displayName = "MessageResponse";

export type MessageAttachmentProps = HTMLAttributes<HTMLDivElement> & {
  data: FileUIPart;
  className?: string;
  onRemove?: () => void;
};

export function MessageAttachment({
  data,
  className,
  onRemove,
  ...props
}: MessageAttachmentProps) {
  const filename = data.filename || "";
  const mediaType =
    data.mediaType?.startsWith("image/") && data.url ? "image" : "file";
  const isImage = mediaType === "image";
  const attachmentLabel = filename || (isImage ? "Image" : "Attachment");

  return (
    <div
      className={cn(
        "group relative size-24 overflow-hidden rounded-lg",
        className
      )}
      {...props}
    >
      {isImage ? (
        <>
          <img
            alt={filename || "attachment"}
            className="size-full object-cover"
            height={100}
            src={data.url}
            width={100}
          />
          {onRemove && (
            <Button
              aria-label="Remove attachment"
              className="absolute top-2 right-2 size-6 rounded-full bg-background/80 p-0 opacity-0 backdrop-blur-sm transition-opacity hover:bg-background group-hover:opacity-100 [&>svg]:size-3"
              onClick={(e) => {
                e.stopPropagation();
                onRemove();
              }}
              type="button"
              variant="ghost"
            >
              <XIcon />
              <span className="sr-only">Remove</span>
            </Button>
          )}
        </>
      ) : (
        <>
          <Tooltip>
            <TooltipTrigger asChild>
              <div className="flex size-full shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                <PaperclipIcon className="size-4" />
              </div>
            </TooltipTrigger>
            <TooltipContent>
              <p>{attachmentLabel}</p>
            </TooltipContent>
          </Tooltip>
          {onRemove && (
            <Button
              aria-label="Remove attachment"
              className="absolute top-2 right-2 size-6 shrink-0 rounded-full bg-background/80 p-0 opacity-0 backdrop-blur-sm transition-opacity hover:bg-accent group-hover:opacity-100 [&>svg]:size-3"
              onClick={(e) => {
                e.stopPropagation();
                onRemove();
              }}
              type="button"
              variant="ghost"
            >
              <XIcon />
              <span className="sr-only">Remove</span>
            </Button>
          )}
        </>
      )}
    </div>
  );
}

export type MessageAttachmentsProps = ComponentProps<"div">;

export function MessageAttachments({
  children,
  className,
  ...props
}: MessageAttachmentsProps) {
  if (!children) {
    return null;
  }

  return (
    <div
      className={cn(
        "ml-auto flex w-fit flex-wrap items-start gap-2",
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}

export type MessageToolbarProps = ComponentProps<"div">;

export const MessageToolbar = ({
  className,
  children,
  ...props
}: MessageToolbarProps) => (
  <div
    className={cn(
      "mt-4 flex w-full items-center justify-between gap-4",
      className
    )}
    {...props}
  >
    {children}
  </div>
);
