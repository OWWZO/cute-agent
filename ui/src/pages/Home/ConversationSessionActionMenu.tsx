import { cn } from "@/lib/utils";
import { Pin, Star, Trash2 } from "lucide-react";

import type { ConversationSessionItem } from "@/services/agentConversation";

type ConversationSessionActionMenuProps = {
  session: ConversationSessionItem;
  canManageFeatured: boolean;
  onManageFeatured: (session: ConversationSessionItem) => void;
  onPin: (session: ConversationSessionItem) => void;
  onDelete: (session: ConversationSessionItem) => void;
};

const menuItemClassName =
  "flex w-full items-center gap-2 px-3 py-2 text-ui-sm transition-colors";
const FEATURE_ACTION_LABEL = "设为精品";
const PIN_ACTION_LABEL = "置顶";
const DELETE_ACTION_LABEL = "删除";

export default function ConversationSessionActionMenu(
  props: ConversationSessionActionMenuProps
) {
  const { session, canManageFeatured, onManageFeatured, onPin, onDelete } =
    props;
  const featuredDisabledReason = session.bindingId
    ? "QQ 会话不能设为精品"
    : "请先让该会话至少产生一轮内容，再设为精品";

  return (
    <div className="absolute right-2 top-full z-10 mt-1 w-36 rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] py-1 shadow-[var(--shadow-md)]">
      <button
        type="button"
        disabled={!canManageFeatured}
        title={canManageFeatured ? undefined : featuredDisabledReason}
        onClick={() => onManageFeatured(session)}
        className={cn(
          menuItemClassName,
          canManageFeatured
            ? "text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-soft)] hover:text-[var(--chat-text)]"
            : "cursor-not-allowed text-[var(--chat-text-muted)] opacity-60"
        )}
      >
        <Star className="h-3.5 w-3.5" />
        <span>{FEATURE_ACTION_LABEL}</span>
      </button>
      <button
        type="button"
        onClick={() => onPin(session)}
        className={`${menuItemClassName} text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-soft)] hover:text-[var(--chat-text)]`}
      >
        <Pin className="h-3.5 w-3.5" />
        <span>{PIN_ACTION_LABEL}</span>
      </button>
      <button
        type="button"
        onClick={() => onDelete(session)}
        className={`${menuItemClassName} text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-soft)] hover:text-[var(--destructive)]`}
      >
        <Trash2 className="h-3.5 w-3.5" />
        <span>{DELETE_ACTION_LABEL}</span>
      </button>
    </div>
  );
}
