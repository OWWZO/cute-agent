import type { ReactNode } from "react";

import {
  DiffCodeFence,
  KimiCodeFence,
} from "@/components/Dialogue/markdown/KimiCodeFence";
import MermaidBlock from "./MermaidBlock";

import { cn } from "@/lib/utils";

/** 终答 Markdown 的代码围栏渲染器形状（`react-markdown` 的 `components.code`）。 */
export type MarkdownCodeBlockProps = {
  className?: string;
  children?: unknown;
  /** `MarkdownPre` 给块级代码注入的标记；`react-markdown` v10 不再传 `inline`。 */
  "data-block"?: string;
};

function childrenToText(children: unknown): string {
  if (children == null || typeof children === "boolean") {
    return "";
  }
  if (typeof children === "string" || typeof children === "number") {
    return String(children);
  }
  if (Array.isArray(children)) {
    return children.map(childrenToText).join("");
  }
  if (typeof children === "object" && "props" in children) {
    return childrenToText(
      (children as { props?: { children?: unknown } }).props?.children
    );
  }
  return "";
}

function cnInlineCode(className?: string) {
  return cn("kimi-inline-code", className);
}

/**
 * 代码围栏渲染器工厂。
 *
 * 做成工厂而不是模块级常量，是因为 `streaming` 必须来自当前渲染上下文 ——
 * 流式期间要跳过 shiki 高亮与 Mermaid（对齐 ZCode
 * `enableSyntaxHighlighting={!renderStreaming}` / `renderMermaid={!renderStreaming}`）。
 */
export const createCodeBlock =
  (streaming: boolean): React.FC<MarkdownCodeBlockProps> =>
    function CodeBlock({ className, children, ...rest }) {
      // 块级围栏由 MarkdownPre 注入 data-block；行内代码没有这个标记。
      const isBlock = Object.prototype.hasOwnProperty.call(rest, "data-block");
      const match = /language-(\w+)/.exec(className || "");
      const trimmed = childrenToText(children).replace(/\n$/, "");

      // Mermaid 解析 + 渲染比 shiki 更重，流式时先按普通代码块展示。
      if (match?.[1] === "mermaid" && !streaming) {
        return <MermaidBlock code={trimmed} />;
      }

      if (isBlock && match?.[1] === "diff") {
        return <DiffCodeFence code={trimmed} streaming={streaming} />;
      }

      if (isBlock && match) {
        // 未知语言由 KimiCodeFence / Shiki 自行回退为纯文本，这里不做协议校验。
        return (
          <KimiCodeFence
            code={trimmed}
            language={match[1]}
            streaming={streaming}
          />
        );
      }

      // 无 language 的围栏仍按代码块渲染，避免单行内容掉进行内 chip
      if (isBlock) {
        return (
          <KimiCodeFence code={trimmed} language="text" streaming={streaming} />
        );
      }

      return (
        <code className={cnInlineCode(className)}>{children as ReactNode}</code>
      );
    };
