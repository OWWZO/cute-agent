import { memo } from "react";
import { SearchIcon } from "lucide-react";
import { pathBreadcrumb } from "@/lib/codeViewerSource";
import { ToolRow, type ToolRowStackPosition } from "./ToolRow";
import { ToolFileChip } from "./ToolFileChip";
import { ToolArgStreamCard } from "./ToolArgStreamCard";
import { extractToolFilePath, toolLabel, toolSummary } from "./toolMeta";
import {
  resolveTaskToolArg,
  resolveTaskToolName,
  resolveTaskToolStatus,
} from "./toolTaskAdapter";
import { isToolArgStreaming, shouldShowToolArgStream } from "./toolStreamPreview";

type ReadToolCallProps = {
  tool: CHAT.Task;
  chat: CHAT.ChatItem;
  stackPosition?: ToolRowStackPosition;
  onOpenToolDiff?: (task: CHAT.Task, chat: CHAT.ChatItem, fileIndex?: number) => void;
};

/**
 * Read 工具卡（对齐 ZCode `ToolCallBlocks/renderers/read.tsx`）。
 *
 * ZCode 这个 renderer 是 `canToggle={false}` + `content={null}`：**永远不展开**，
 * 正文只由「可点击的文件名 chip」承担，内容一律交给右侧代码查看器。
 * 所以这里既没有入参段也没有结果段 —— 读文件的结果是整份文件正文，
 * 塞进消息流只会把对话淹掉。出错时也一样：报错改由摘要行的状态词 tooltip 承载。
 */
export const ReadToolCall = memo(function ReadToolCall({
  tool,
  chat,
  stackPosition = "single",
  onOpenToolDiff,
}: ReadToolCallProps) {
  const name = resolveTaskToolName(tool);
  const arg = resolveTaskToolArg(tool);
  const status = resolveTaskToolStatus(tool);
  const argStreaming = isToolArgStreaming(tool);
  const showArgStream = shouldShowToolArgStream(tool);
  const summary = toolSummary(name, arg);
  const filePath = extractToolFilePath(arg);
  // ZCode `renderFilePath`：文件名之后补一段**父目录**路径。
  // chip 已经显示 basename（`ToolFileChip` 内部取 `pathBaseName`），这里再渲染全文
  // 会让 `src/a/bar.ts` 显示成 `bar.ts src/a/bar.ts` —— 文件名重复两遍。
  // 裸文件名时 `parents` 为空，同样不重复渲染。
  const parentDir = filePath ? pathBreadcrumb(filePath).parents.join("/") : "";

  if (showArgStream) {
    return (
      <ToolArgStreamCard
        icon={<SearchIcon className="size-4" />}
        name={toolLabel(name)}
        summary={summary}
        text={arg}
        streaming={argStreaming}
        testId="read-tool-arg-stream"
      />
    );
  }

  if (status === "ok" && !filePath && !summary.trim()) {
    return null;
  }

  return (
    <ToolRow
      status={status}
      icon={<SearchIcon className="size-4" />}
      name={toolLabel(name)}
      arg={summary}
      animateSummary={status === "running"}
      argNode={
        filePath ? (
          <ToolFileChip
            path={filePath}
            onOpen={
              onOpenToolDiff ? () => onOpenToolDiff(tool, chat) : undefined
            }
          />
        ) : undefined
      }
      secondary={
        parentDir ? (
          <span className="kimi-tool-row-secondary" title={filePath}>
            {parentDir}
          </span>
        ) : undefined
      }
      open={false}
      expandable={false}
      stacked={stackPosition !== "single"}
      stackPosition={stackPosition}
    />
  );
});
