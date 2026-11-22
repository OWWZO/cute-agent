import { BotIcon, CheckIcon, ChevronDownIcon, NetworkIcon } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import type { CollaborationMode } from "@/utils/agentMode";

export interface AgentProfileOption {
  agentKey: string;
  displayName: string;
  whenToUse?: string;
}

type Props = {
  agents: AgentProfileOption[];
  value?: string | null;
  collaborationMode: CollaborationMode;
  disabled?: boolean;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onChange: (agentKey: string) => void;
};

export default function AgentProfilePicker({
  agents,
  value,
  collaborationMode,
  disabled = false,
  loading = false,
  error = null,
  onRetry,
  onChange,
}: Props) {
  const isCluster = collaborationMode === "cluster";
  const selected =
    agents.find((agent) => agent.agentKey === value) ||
    (value
      ? {
          agentKey: value,
          displayName: value,
          whenToUse: "该 Agent 当前未在启用目录中，请新建会话或恢复配置。",
        }
      : agents[0]) ||
    null;
  const triggerText = isCluster
    ? "协调者"
    : loading
      ? "加载智能体…"
      : selected?.displayName || "选择智能体";

  return (
    <div className="flex min-w-0 items-center gap-2">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            disabled={disabled || isCluster || loading || agents.length === 0}
            className="group inline-flex h-9 min-w-0 max-w-[280px] select-none items-center gap-2 rounded-full border border-black/[0.07] bg-white/70 py-1 pl-1 pr-3 text-ui-caption font-medium text-[var(--chat-text)] shadow-[inset_0_1px_0_rgba(255,255,255,0.9),0_1px_2px_rgba(0,0,0,0.04),0_12px_32px_-14px_rgba(0,0,0,0.28)] backdrop-blur-2xl backdrop-saturate-150 transition-[background-color,box-shadow,transform] duration-200 ease-out hover:bg-white/85 hover:shadow-[inset_0_1px_0_rgba(255,255,255,0.95),0_1px_3px_rgba(0,0,0,0.05),0_16px_38px_-14px_rgba(0,0,0,0.3)] active:scale-[0.98] focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-[var(--color-accent-soft)] disabled:pointer-events-none disabled:opacity-60 motion-reduce:transition-none motion-reduce:active:scale-100 dark:border-white/10 dark:bg-white/[0.07] dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.08),0_1px_2px_rgba(0,0,0,0.4),0_16px_40px_-16px_rgba(0,0,0,0.8)] dark:hover:bg-white/[0.1] dark:hover:shadow-[inset_0_1px_0_rgba(255,255,255,0.1),0_1px_3px_rgba(0,0,0,0.45),0_18px_44px_-16px_rgba(0,0,0,0.85)]"
            data-testid="agent-profile-picker"
            title={isCluster ? "集群模式由通用协调者执行" : selected?.whenToUse}
            aria-label={`当前智能体：${triggerText}`}
          >
            <span className="grid size-7 shrink-0 place-items-center rounded-full bg-[var(--color-accent-soft)] text-[var(--chat-accent)] shadow-[inset_0_0_0_1px_rgba(255,255,255,0.55)] dark:shadow-[inset_0_0_0_1px_rgba(255,255,255,0.06)]">
              {isCluster ? (
                <NetworkIcon className="size-4" strokeWidth={1.8} />
              ) : (
                <BotIcon className="size-4" strokeWidth={1.8} />
              )}
            </span>
            <span className="hidden shrink-0 text-ui-xs text-[var(--chat-text-muted)] sm:inline">
              当前智能体
            </span>
            <span className="hidden h-3.5 w-px shrink-0 bg-black/10 sm:block dark:bg-white/15" />
            <span className="min-w-0 truncate">{triggerText}</span>
            {!isCluster ? (
              <ChevronDownIcon className="size-3.5 shrink-0 text-[var(--chat-text-muted)] transition-transform duration-200 group-data-[state=open]:rotate-180" />
            ) : null}
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="start"
          sideOffset={8}
          className="w-[300px] rounded-2xl border border-black/[0.06] bg-[var(--chat-surface)]/95 p-1.5 shadow-[0_24px_60px_-18px_rgba(0,0,0,0.3),0_2px_8px_rgba(0,0,0,0.06)] ring-0 backdrop-blur-2xl dark:border-white/10"
        >
          <DropdownMenuLabel className="px-2.5 pb-1.5 pt-2 text-ui-sm font-medium text-[var(--chat-text-muted)]">
            选择智能体
          </DropdownMenuLabel>
          {agents.map((agent) => {
            const active = agent.agentKey === selected?.agentKey;
            return (
              <DropdownMenuItem
                key={agent.agentKey}
                className="items-start gap-2.5 rounded-xl px-2 py-2.5 focus:bg-[var(--chat-surface-soft)]"
                onSelect={() => onChange(agent.agentKey)}
              >
                <span
                  className={cn(
                    "mt-0.5 grid size-6 shrink-0 place-items-center rounded-full bg-[var(--chat-surface-soft)] text-[var(--chat-text-soft)]",
                    active &&
                      "bg-[var(--color-accent-soft)] text-[var(--chat-accent)]"
                  )}
                >
                  <BotIcon className="size-3.5" strokeWidth={1.8} />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-ui-caption font-medium">
                    {agent.displayName}
                  </span>
                  {agent.whenToUse ? (
                    <span className="mt-0.5 line-clamp-2 block text-ui-sm leading-4 text-[var(--chat-text-muted)]">
                      {agent.whenToUse}
                    </span>
                  ) : null}
                </span>
                <CheckIcon
                  className={cn(
                    "mt-1 size-4 shrink-0 text-[var(--chat-accent)]",
                    !active && "opacity-0"
                  )}
                />
              </DropdownMenuItem>
            );
          })}
        </DropdownMenuContent>
      </DropdownMenu>
      {error ? (
        <button
          type="button"
          className="inline-flex h-7 shrink-0 items-center rounded-full bg-[var(--color-danger-soft)] px-2.5 text-ui-xs font-medium text-[var(--color-danger)] transition-opacity hover:opacity-80"
          onClick={onRetry}
          title={error}
        >
          加载失败，重试
        </button>
      ) : null}
    </div>
  );
}
