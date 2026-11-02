import { Bold, Code2, Heading2, List, ListOrdered } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

type Props = {
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
};

type SelectionResult = {
  next: string;
  selectionStart: number;
  selectionEnd: number;
};

/** 在 textarea 选区插入 Markdown 包裹/前缀（轻量 Aa 工具条，不对齐 Tiptap） */
function wrapSelection(
  value: string,
  start: number,
  end: number,
  before: string,
  after: string,
  placeholder: string
): SelectionResult {
  const selected = value.slice(start, end);
  const body = selected || placeholder;
  const next = value.slice(0, start) + before + body + after + value.slice(end);
  const bodyStart = start + before.length;
  // 空选区插入占位文本时选中占位符，用户可直接输入覆盖；已有选区时光标落在包裹块之后。
  if (!selected) {
    return {
      next,
      selectionStart: bodyStart,
      selectionEnd: bodyStart + body.length,
    };
  }
  const cursor = bodyStart + body.length + after.length;
  return {
    next,
    selectionStart: cursor,
    selectionEnd: cursor,
  };
}

function prefixLines(
  value: string,
  start: number,
  end: number,
  prefix: string
): SelectionResult {
  const lineStart = value.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
  const segment = value.slice(lineStart, end);
  const lines = segment.split("\n");
  const rewritten = lines
    .map((line) => (line.startsWith(prefix) ? line : `${prefix}${line || ""}`))
    .join("\n");
  const next = value.slice(0, lineStart) + rewritten + value.slice(end);
  const cursor = lineStart + rewritten.length;
  return {
    next,
    selectionStart: cursor,
    selectionEnd: cursor,
  };
}

const btnClass =
  "inline-flex h-7 w-7 items-center justify-center rounded-md text-foreground-subtle transition-colors hover:bg-black/[0.04] hover:text-foreground disabled:opacity-40";

type MarkdownAction = {
  title: string;
  content: ReactNode;
  className?: string;
  run: (value: string, start: number, end: number) => SelectionResult;
};

const ACTIONS: MarkdownAction[] = [
  {
    title: "加粗",
    content: <Bold className="size-3.5" />,
    run: (v, s, e) => wrapSelection(v, s, e, "**", "**", "加粗"),
  },
  {
    title: "二级标题",
    content: <Heading2 className="size-3.5" />,
    run: (v, s, e) => prefixLines(v, s, e, "## "),
  },
  {
    title: "无序列表",
    content: <List className="size-3.5" />,
    run: (v, s, e) => prefixLines(v, s, e, "- "),
  },
  {
    title: "有序列表",
    content: <ListOrdered className="size-3.5" />,
    run: (v, s, e) => prefixLines(v, s, e, "1. "),
  },
  {
    title: "行内代码",
    content: <Code2 className="size-3.5" />,
    run: (v, s, e) => wrapSelection(v, s, e, "`", "`", "code"),
  },
  {
    title: "代码块",
    content: "</>",
    className: "w-auto px-1.5 text-ui-xs font-medium",
    run: (v, s, e) => wrapSelection(v, s, e, "```\n", "\n```", "code"),
  },
];

const MarkdownBar: CuteType.FC<Props> = ({
  textareaRef,
  value,
  onChange,
  disabled,
}) => {
  const apply = (fn: MarkdownAction["run"]) => {
    const el = textareaRef.current;
    const start = el?.selectionStart ?? value.length;
    const end = el?.selectionEnd ?? value.length;
    const { next, selectionStart, selectionEnd } = fn(value, start, end);
    onChange(next);
    requestAnimationFrame(() => {
      if (!el) return;
      el.focus();
      el.setSelectionRange(selectionStart, selectionEnd);
    });
  };

  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-0.5 border-b border-black/[0.04] px-2.5 py-1"
      )}
    >
      {ACTIONS.map((action) => (
        <button
          key={action.title}
          type="button"
          disabled={disabled}
          className={cn(btnClass, action.className)}
          title={action.title}
          onClick={() => apply(action.run)}
        >
          {action.content}
        </button>
      ))}
    </div>
  );
};

export default MarkdownBar;
