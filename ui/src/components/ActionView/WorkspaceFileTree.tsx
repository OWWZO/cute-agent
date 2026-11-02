import { memo, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import {
  ChevronDown,
  ChevronRight,
  FileCode,
  FileIcon,
  FileSpreadsheet,
  FileText,
  Folder,
  ImageIcon,
} from "lucide-react";
import {
  formatWorkspaceBytes,
  workspaceFileKey,
  type WorkspaceFileItem,
  type WorkspaceTreeNode,
} from "./workspaceFiles";

type WorkspaceFileTreeProps = {
  nodes: WorkspaceTreeNode[];
  selectedFileKey?: string;
  onSelectFile?: (file: WorkspaceFileItem) => void;
  depth?: number;
};

/** 单个目录一次最多渲染的行数；超出部分按「显示更多」分页，避免大目录一次铺满 DOM。 */
const TREE_PAGE_SIZE = 50;

function TreeMoreButton({
  remaining,
  onMore,
}: {
  remaining: number;
  onMore: () => void;
}) {
  if (remaining <= 0) {
    return null;
  }
  return (
    <button
      type="button"
      className="ml-4 flex h-7 items-center rounded-lg border border-transparent px-2 text-ui-xs text-foreground-subtle transition-colors hover:bg-surface-hover hover:text-foreground"
      onClick={onMore}
    >
      显示更多（剩余 {remaining}）
    </button>
  );
}

// ZCode WorkspaceFileTreeRowView.tsx:270 —— 行内图标槽统一 size-4、走三级前景。
// 类型色改走语义 token（DESIGN.md 禁 Tailwind 调色板色），保留"颜色即类型"的识别性。
const getFileIcon = (type: string) => {
  const ext = (type || "").toLowerCase();
  switch (ext) {
    case "png":
    case "jpg":
    case "jpeg":
    case "gif":
    case "webp":
    case "svg":
    case "bmp":
      return <ImageIcon className="size-4 text-foreground-subtle" />;
    case "csv":
    case "xlsx":
    case "xls":
      return <FileSpreadsheet className="size-4 text-success" />;
    case "html":
    case "htm":
    case "code":
    case "js":
    case "ts":
    case "tsx":
    case "jsx":
    case "py":
    case "java":
    case "css":
      return <FileCode className="size-4 text-brand" />;
    case "pdf":
      return <FileText className="size-4 text-destructive" />;
    case "doc":
    case "docx":
    case "md":
    case "markdown":
    case "txt":
      return <FileText className="size-4 text-foreground-subtle" />;
    default:
      return <FileIcon className="size-4 text-foreground-subtlest" />;
  }
};

/**
 * ZCode WorkspaceFileTreeRowView.tsx:218-224 —— 文件树行的唯一真源。
 * h-7 / rounded-lg / border / hover:bg-surface-hover；选中态只换描边色，
 * 不铺实心底色（`border-input-border-focused`）。
 */
const treeRowClass = (active: boolean) =>
  cn(
    "group/file-tree-row relative flex h-7 w-full min-w-0 items-center gap-1.5 rounded-lg border pr-2 py-1 text-left text-ui-base text-foreground outline-none transition-colors",
    // outline-none 会一并抹掉键盘焦点提示，这里用 inset ring 补回可见焦点。
    "focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-input-border-focused",
    active
      ? "border-input-border-focused hover:bg-surface-hover"
      : "border-transparent hover:bg-surface-hover"
  );

/** 目录行与文件行共用的按钮外壳：图标槽 / 截断名称 / 尾部元信息。 */
function TreeNodeRow({
  active = false,
  depth,
  title,
  onClick,
  leading,
  name,
  trailing,
}: {
  active?: boolean;
  depth: number;
  title: string;
  onClick: () => void;
  leading: ReactNode;
  name: string;
  trailing: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={treeRowClass(active)}
      style={{ marginLeft: depth * 12 }}
      title={title}
    >
      <span className="flex size-4 shrink-0 items-center justify-center text-foreground-subtle">
        {leading}
      </span>
      <span className="min-w-0 flex-1 truncate">{name}</span>
      {trailing}
    </button>
  );
}

const TreeNode = memo(function TreeNode({
  node,
  selectedFileKey,
  onSelectFile,
  depth,
}: {
  node: WorkspaceTreeNode;
  selectedFileKey?: string;
  onSelectFile?: (file: WorkspaceFileItem) => void;
  depth: number;
}) {
  const [expanded, setExpanded] = useState(depth === 0);
  const [visibleCount, setVisibleCount] = useState(TREE_PAGE_SIZE);

  if (node.kind === "dir") {
    const visibleChildren = node.children.slice(0, visibleCount);
    return (
      <div>
        <TreeNodeRow
          depth={depth}
          title={`${node.name} · ${formatWorkspaceBytes(node.size)}`}
          onClick={() => setExpanded((prev) => !prev)}
          leading={<Folder className="size-4" />}
          name={node.name}
          trailing={
            <>
              <span className="shrink-0 text-ui-xs tabular-nums text-foreground-subtlest">
                {formatWorkspaceBytes(node.size)}
              </span>
              {expanded ? (
                <ChevronDown className="size-3 shrink-0 text-foreground-subtlest" />
              ) : (
                <ChevronRight className="size-3 shrink-0 text-foreground-subtlest" />
              )}
            </>
          }
        />
        {expanded ? (
          <div className="mt-0.5 flex flex-col gap-0.5">
            {visibleChildren.map((child) => (
              <TreeNode
                key={child.path}
                node={child}
                selectedFileKey={selectedFileKey}
                onSelectFile={onSelectFile}
                depth={depth + 1}
              />
            ))}
            <TreeMoreButton
              remaining={node.children.length - visibleChildren.length}
              onMore={() => setVisibleCount((prev) => prev + TREE_PAGE_SIZE)}
            />
          </div>
        ) : null}
      </div>
    );
  }

  const key = workspaceFileKey(node.file);
  const active = key === selectedFileKey;
  return (
    <TreeNodeRow
      active={active}
      depth={depth}
      title={
        node.file.missing
          ? node.file.missingReason || "内容不可读取"
          : `${node.name} · ${formatWorkspaceBytes(node.size)}`
      }
      onClick={() => onSelectFile?.(node.file)}
      leading={getFileIcon(node.file.type)}
      name={node.name}
      trailing={
        node.file.missing ? (
          <span className="max-w-24 shrink-0 truncate text-ui-xs text-destructive">
            {node.file.missingReason || "不可读取"}
          </span>
        ) : (
          <span className="shrink-0 text-ui-xs tabular-nums text-foreground-subtlest">
            {formatWorkspaceBytes(node.size)}
          </span>
        )
      }
    />
  );
});

const WorkspaceFileTree = memo(function WorkspaceFileTree(
  props: WorkspaceFileTreeProps
) {
  const { nodes, selectedFileKey, onSelectFile, depth = 0 } = props;
  const [visibleCount, setVisibleCount] = useState(TREE_PAGE_SIZE);
  if (!nodes.length) {
    return (
      <div className="px-2 py-8 text-center text-ui-sm text-foreground-subtle">
        当前会话暂无文件
      </div>
    );
  }
  const visibleNodes = nodes.slice(0, visibleCount);
  return (
    <div className="flex flex-col gap-0.5">
      {visibleNodes.map((node) => (
        <TreeNode
          key={node.path}
          node={node}
          selectedFileKey={selectedFileKey}
          onSelectFile={onSelectFile}
          depth={depth}
        />
      ))}
      <TreeMoreButton
        remaining={nodes.length - visibleNodes.length}
        onMore={() => setVisibleCount((prev) => prev + TREE_PAGE_SIZE)}
      />
    </div>
  );
});

WorkspaceFileTree.displayName = "WorkspaceFileTree";

export default WorkspaceFileTree;
