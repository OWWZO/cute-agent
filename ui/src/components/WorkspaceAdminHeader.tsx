import type { ReactNode } from "react";
import { ArrowLeft, type LucideIcon } from "lucide-react";
import { Link } from "react-router-dom";
import { cn } from "@/lib/utils";

import WorkspaceToolSwitcher from "@/components/WorkspaceToolSwitcher";
import { ROUTES } from "@/router/routes";

import "@/styles/workspace-admin.css";

const BACK_LABEL = "返回首页";
const HEADER_KICKER = "Cute / 工作台";

type WorkspaceAdminHeaderProps = {
  title: string;
  description: string;
  icon: LucideIcon;
  embedded?: boolean;
  actions?: ReactNode;
};

export default function WorkspaceAdminHeader({
  title,
  description,
  icon: Icon,
  embedded,
  actions,
}: WorkspaceAdminHeaderProps) {
  return (
    <header className="workspace-admin-header">
      <div
        className={cn(
          "workspace-admin-header-inner",
          embedded && "workspace-admin-header-inner-embedded",
        )}
      >
        <div className="workspace-admin-heading">
          {!embedded ? (
            <Link
              to={ROUTES.HOME}
              target="_blank"
              rel="noreferrer"
              className="workspace-admin-back"
              aria-label={BACK_LABEL}
              title={BACK_LABEL}
            >
              <ArrowLeft aria-hidden="true" />
            </Link>
          ) : null}
          <span className="workspace-admin-mark" aria-hidden="true">
            <Icon />
          </span>
          <div className="workspace-admin-heading-copy">
            <span className="workspace-admin-kicker">{HEADER_KICKER}</span>
            <h1 className="workspace-admin-title">{title}</h1>
            <p className="workspace-admin-description">{description}</p>
          </div>
        </div>

        <div className="workspace-admin-header-actions">
          {!embedded ? <WorkspaceToolSwitcher /> : null}
          {actions}
        </div>
      </div>
    </header>
  );
}
