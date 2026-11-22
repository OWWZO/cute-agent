import { memo, useMemo } from "react";
import { FolderTree } from "lucide-react";

import WorkspaceFileTree from "./WorkspaceFileTree";
import {
  buildWorkspaceTree,
  type WorkspaceFileItem,
} from "./workspaceFiles";

type WorkspaceHomePanelProps = {
  files: WorkspaceFileItem[];
  onOpenFile: (file: WorkspaceFileItem) => void;
};

const WorkspaceHomePanel = memo(function WorkspaceHomePanel({
  files,
  onOpenFile,
}: WorkspaceHomePanelProps) {
  const tree = useMemo(() => buildWorkspaceTree(files), [files]);

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      {files.length ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-2 py-3 scrollbar-thin">
          <div className="mb-1 flex items-center justify-between px-2.5 py-1">
            <span className="text-ui-xs font-medium tracking-[0.08em] text-foreground-subtlest">
              文件
            </span>
            <span className="text-ui-xs tabular-nums text-foreground-subtlest">
              {files.length}
            </span>
          </div>
          <WorkspaceFileTree nodes={tree} onSelectFile={onOpenFile} />
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 items-center justify-center px-8 pb-12">
          <div className="max-w-[300px] text-center">
            <div className="mx-auto mb-4 flex size-12 items-center justify-center rounded-2xl border border-[var(--color-line)] bg-surface-sunken text-foreground-subtle shadow-sm">
              <FolderTree className="size-5" />
            </div>
            <p className="text-ui-base font-medium text-foreground">
              还没有工作区内容
            </p>
            <p className="mt-1.5 text-ui-sm leading-6 text-foreground-subtle">
              对话生成的文件和预览会出现在这里；任务与子智能体仍可从对话中的卡片打开。
            </p>
          </div>
        </div>
      )}
    </div>
  );
});

WorkspaceHomePanel.displayName = "WorkspaceHomePanel";

export default WorkspaceHomePanel;
