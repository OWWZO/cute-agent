import { cn } from "@/lib/utils";
import { Blocks, Bot, Cpu, DatabaseZap, MessageSquare, WandSparkles } from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";

import { buildHomeViewPath, type SidebarView } from "@/pages/Home/sidebarViews";

type WorkspaceToolItem = {
  key: SidebarView;
  label: string;
  icon: typeof DatabaseZap;
};

const workspaceToolItems: WorkspaceToolItem[] = [
  {
    key: "mrag",
    label: "MRAG 文件工作台",
    icon: DatabaseZap,
  },
  {
    key: "image-generation",
    label: "绘图智能体",
    icon: WandSparkles,
  },
  {
    key: "sub-agents",
    label: "子 Agent 装配",
    icon: Bot,
  },
  {
    key: "models",
    label: "模型接入",
    icon: Cpu,
  },
  {
    key: "capabilities",
    label: "能力库",
    icon: Blocks,
  },
  {
    key: "channels",
    label: "机器人管理",
    icon: MessageSquare,
  },
];

const WorkspaceToolSwitcher: CuteType.FC = ({ className }) => {
  // 工作台模块现在都落在主页 `?view=` 上，激活态只能看 query，不能再比 pathname。
  const [searchParams] = useSearchParams();
  const activeView = searchParams.get("view") ?? "chat";

  return (
    <div
      className={cn(
        "workspace-tool-switcher inline-flex max-w-full items-center gap-0.5 overflow-x-auto rounded-lg border border-[var(--color-line)] bg-[var(--color-surface-sunken)] p-1",
        className,
      )}
      role="navigation"
      aria-label="工作台模块"
    >
      {workspaceToolItems.map((item) => {
        const active = activeView === item.key;

        return (
          <Link
            key={item.key}
            to={buildHomeViewPath(item.key)}
            aria-current={active ? "page" : undefined}
            className={cn(
              "group inline-flex min-h-[30px] shrink-0 items-center gap-1.5 rounded-md border border-transparent px-2.5 text-ui-caption font-medium transition-[background-color,border-color,color] duration-[var(--duration-base)] ease-[var(--ease-out)]",
              active
                ? "border-[var(--color-accent-bd)] bg-[var(--color-accent-soft)] text-[var(--color-accent)]"
                : "text-[var(--color-text-muted)] hover:bg-[var(--color-hover)] hover:text-[var(--color-text)]",
            )}
          >
            <item.icon className="h-4 w-4 shrink-0" aria-hidden="true" />
            <span className="max-w-[132px] truncate">{item.label}</span>
          </Link>
        );
      })}
    </div>
  );
};

export default WorkspaceToolSwitcher;
