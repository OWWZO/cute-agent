import type { ReactNode } from "react";
import { MessageSquareTextIcon, MousePointer2Icon, QuoteIcon } from "lucide-react";
import type { SerializedPromptContexts } from "@/lib/promptContexts";

const CARD_CLASS =
  "flex max-w-[320px] min-w-0 items-center gap-1.5 rounded-full border border-[var(--chat-border)] bg-[var(--chat-surface-muted)] px-2.5 py-1 text-ui-xs text-[var(--chat-text-soft)]";

const CONTEXT_ICON_CLASS = "size-3.5 shrink-0 text-[var(--chat-text-muted)]";

function truncate(text: string, max = 48): string {
  const normalized = text.replace(/\s+/g, " ").trim();
  return normalized.length > max ? `${normalized.slice(0, max)}…` : normalized;
}

/** 三类上下文共用同一张卡片：容器、tooltip 与截断文本只在这里维护。 */
function renderContextCard(options: {
  key: string;
  title: string;
  icon: ReactNode;
  label?: ReactNode;
  text?: string;
}) {
  return (
    <div key={options.key} className={CARD_CLASS} title={options.title}>
      {options.icon}
      {options.label}
      {options.text ? <span className="truncate">{options.text}</span> : null}
    </div>
  );
}

/**
 * 用户消息里回显的预览区上下文（只读）。
 *
 * 用户消息正文里其实拼着 `# Code comments:` / `# Web page elements:` / `# userselect:`
 * 三段尾块；`Dialogue` 先用 `parseComposerPromptContexts` 把它们剥出来，
 * 正文只渲染 `visibleContent`，剥出来的上下文在这里渲染成卡片，
 * 否则用户会看到一大坨原始 markdown 糊在气泡里。
 */
export function UserPromptContexts({ contexts }: { contexts: SerializedPromptContexts }) {
  const hasAny =
    contexts.codeComments.length > 0 ||
    contexts.webElements.length > 0 ||
    contexts.conversationSelections.length > 0;
  if (!hasAny) return null;

  return (
    <div className="flex w-full flex-wrap justify-end gap-1.5">
      {contexts.codeComments.map((comment) => {
        const lineLabel =
          comment.startLine === comment.endLine
            ? `L${comment.startLine}`
            : `L${comment.startLine}-L${comment.endLine}`;
        return renderContextCard({
          key: `code-comment-${comment.id}`,
          title: comment.comment || comment.sourcePath,
          icon: <MessageSquareTextIcon className={CONTEXT_ICON_CLASS} />,
          label: (
            <>
              <span className="shrink-0 font-medium text-[var(--chat-text)]">
                {comment.sourceTitle}
              </span>
              <span className="shrink-0 font-mono">{lineLabel}</span>
            </>
          ),
          text: comment.comment.trim() ? `· ${truncate(comment.comment)}` : undefined,
        });
      })}

      {contexts.webElements.map((element) => {
        const title =
          element.accessibleName ||
          element.text ||
          element.selector ||
          element.tagName.toLowerCase();
        return renderContextCard({
          key: `web-element-${element.id}`,
          title: [element.pageTitle || element.pageUrl, element.selector]
            .filter(Boolean)
            .join("\n"),
          icon: <MousePointer2Icon className={CONTEXT_ICON_CLASS} />,
          label: (
            <span className="shrink-0 font-medium text-[var(--chat-text)]">
              {element.tagName.toLowerCase()}
            </span>
          ),
          text: truncate(title),
        });
      })}

      {contexts.conversationSelections.map((selection) =>
        renderContextCard({
          key: `selection-${selection.id}`,
          title: selection.text,
          icon: <QuoteIcon className={CONTEXT_ICON_CLASS} />,
          label: selection.path ? (
            <span className="shrink-0 font-medium text-[var(--chat-text)]">
              {selection.path.split(/[\\/]/).pop() || selection.path}
            </span>
          ) : undefined,
          text: truncate(selection.text),
        })
      )}
    </div>
  );
}

export default UserPromptContexts;
