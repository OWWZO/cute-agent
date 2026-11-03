import { memo, useEffect, useMemo, useState } from "react";
import { PencilIcon } from "lucide-react";
import { ToolRow, type ToolRowStackPosition } from "./ToolRow";
import { ToolFileChip } from "./ToolFileChip";
import { ToolOutputBlock } from "./ToolOutputBlock";
import { ToolParametersBlock } from "./ToolParametersBlock";
import { ToolArgStreamCard } from "./ToolArgStreamCard";
import { EditInlineDiffContent } from "./EditInlineDiffContent";
import {
  buildWriteDiffCode,
  editDiffStats,
  extractEditFileSummaries,
  summarizeEditFiles,
  type EditFileSummary,
} from "./toolDiff";
import { ToolDiffCount } from "./ToolDiffCount";
import { normalizeToolName, toolLabel, toolSummary } from "./toolMeta";
import {
  resolveTaskToolArg,
  resolveTaskToolName,
  resolveTaskToolOutput,
  resolveTaskToolPatch,
  resolveTaskToolStatus,
  resolveToolKey,
} from "./toolTaskAdapter";
import { useToolCardOpen } from "./useToolCardOpen";
import {
  formatToolStreamChip,
  isToolArgStreaming,
  resolveToolStreamPath,
  shouldShowToolArgStream,
} from "./toolStreamPreview";
import { parseToolJson } from "./ToolJsonBlock";

type EditToolCallProps = {
  tool: CHAT.Task;
  chat: CHAT.ChatItem;
  stackPosition?: ToolRowStackPosition;
  defaultExpanded?: boolean;
  onOpenToolDiff?: (task: CHAT.Task, chat: CHAT.ChatItem, fileIndex?: number) => void;
};

/**
 * Edit / Write 工具卡（对齐 ZCode `ToolCallBlocks/renderers/edit.tsx`）。
 *
 * 展开体的构成由「有没有内联 diff」决定，与 ZCode 的策略层一致：
 * - **有 patch** → 展开体就是内联 diff。ZCode 在这个分支把 `inlinePreview` 显式置成
 *   `{type:"none"}`，连带把通用 `Parameters` 关掉 —— 变更内容已经由 diff 表达，
 *   再摊一遍 `old_string`/`new_string` 的 JSON 只是噪音。
 * - **没有 patch**（`replace_all` 等）→ 退回「参数 + 结果」两段。
 * - **失败态** → 只留错误文本（ZCode 错误分支强制 `showInput: false`）。
 *
 * 多文件时主行只做总览，逐文件拆成 `border-l` 左导轨下的可展开子卡，各自带自己的 diff。
 */
export const EditToolCall = memo(function EditToolCall({
  tool,
  chat,
  stackPosition = "single",
  defaultExpanded,
  onOpenToolDiff,
}: EditToolCallProps) {
  const name = resolveTaskToolName(tool);
  const arg = resolveTaskToolArg(tool);
  const status = resolveTaskToolStatus(tool);
  const output = resolveTaskToolOutput(tool);
  const argStreaming = isToolArgStreaming(tool);
  const showArgStream = shouldShowToolArgStream(tool);
  const streamPath = showArgStream ? resolveToolStreamPath(arg) : "";
  let summary = toolSummary(name, arg);
  if (showArgStream) {
    if (streamPath) {
      summary = streamPath;
    } else if (argStreaming) {
      summary = "生成参数中…";
    }
  }
  // 参数流期间 arg 每帧都在增长，parse 与 LCS diff 都不便宜，必须按内容记忆。
  const inputJson = useMemo(() => parseToolJson(arg), [arg]);
  const isError = status === "error";
  const errorText = isError ? output.join("\n").trim() : "";

  /**
   * 内联 diff 的高亮门禁：工具自身还没落定就跳过 shiki。
   *
   * 对齐 ZCode `message.tsx` 的 `enableSyntaxHighlighting={!renderStreaming}`。
   * 这里刻意用**工具自己的状态**而不是 `chat.loading` —— 同一轮里别的工具还在跑时，
   * 已经完成的这张卡内容不会再变，没必要跟着一起丢掉高亮。
   */
  const isStreaming = status === "running" || argStreaming;

  // 后端产出的 unified diff（带真实行号 + 3 行上下文）。拿不到时由 toolDiff 回退到片段 LCS。
  // toolResult 里现在会带整份 patch，JSON.parse 不宜每次渲染都跑，所以按 tool 记忆。
  const backendPatch = useMemo(() => resolveTaskToolPatch(tool), [tool]);

  // 逐文件摘要。单文件时数组长度为 1，走同一条渲染路径。
  const fileSummaries = useMemo(
    () =>
      showArgStream || isError
        ? []
        : extractEditFileSummaries(name, arg, backendPatch),
    [name, arg, backendPatch, showArgStream, isError]
  );

  // 只算一次 diff 统计：chip 文案与翻页数字节点共用同一份结果。
  // 单文件摘要里已经带了 patch 统计，直接复用，避免同一份 old/new 再跑一次 LCS。
  const diffStats = useMemo(() => {
    if (showArgStream || isError) return null;
    const first = fileSummaries[0];
    // 只有真的产出了 patch / 统计时才复用；replace_all 这类空摘要继续走 editDiffStats 的 null 分支。
    if (first && (first.patch || first.added || first.removed)) {
      return {
        added: first.added,
        removed: first.removed,
      };
    }
    if (fileSummaries.length <= 1) {
      return editDiffStats(name, arg, backendPatch);
    }
    // 多文件只展示 totalStats，不再白算一份 LCS。
    return null;
  }, [showArgStream, isError, fileSummaries, name, arg, backendPatch]);

  const isMultiFile = fileSummaries.length > 1;
  const totalStats = useMemo(
    () => (isMultiFile ? summarizeEditFiles(fileSummaries) : null),
    [isMultiFile, fileSummaries]
  );

  // 摘要行尾部只保留 diffCount（`+A −B`）。`edited` 这类附加 chip 已移除：
  // ZCode 的 edit 卡没有文本 chip，变更信息全部由 diffCount 承担。
  const streamChip = showArgStream ? formatToolStreamChip(arg, arg) : "";
  const chipNode = diffStats ? (
    <ToolDiffCount added={diffStats.added} removed={diffStats.removed} />
  ) : undefined;

  // 默认折叠：入参流分支提前 return 自己的预览面板、不看 `open`，所以不能拿
  // `showArgStream` 当展开种子 —— 否则参数流结束切回常规卡片时 diff 是摊开的。
  const [open, setOpen] = useToolCardOpen(
    resolveToolKey(tool),
    Boolean(defaultExpanded)
  );
  const [userToggled, setUserToggled] = useState(false);

  useEffect(() => {
    if (userToggled) {
      return;
    }
    if (defaultExpanded) {
      setOpen(true);
    }
  }, [defaultExpanded, userToggled, setOpen]);

  const kind = normalizeToolName(name);
  const icon = <PencilIcon className="size-4" />;

  // write 走合成 patch（整文件新增）；edit / multi_edit 直接用逐文件摘要里的 patch。
  const singlePatch = useMemo(() => {
    if (showArgStream || isError) return undefined;
    const fromSummary = fileSummaries[0]?.patch;
    if (fromSummary) return fromSummary;
    if (kind !== "write") return undefined;
    return buildWriteDiffCode(name, arg) ?? undefined;
  }, [showArgStream, isError, fileSummaries, kind, name, arg]);

  // write 的合成 patch 不会进 fileSummaries（那边 patch 恒为 undefined），
  // 所以单文件路径要单独取一次路径。
  const singlePath = fileSummaries[0]?.path;

  if (showArgStream) {
    return (
      <ToolArgStreamCard
        icon={icon}
        name={toolLabel(name)}
        summary={summary}
        chip={streamChip}
        text={arg}
        streaming={argStreaming}
        testId="edit-tool-arg-stream"
      />
    );
  }

  // ---- 多文件：主行总览 + border-l 导轨下的逐文件可展开子卡 ----
  if (isMultiFile) {
    const [firstFile] = fileSummaries;

    return (
      <div className="kimi-tool-multi-edit" data-testid="edit-tool-multi-file">
        <ToolRow
          status={status}
          icon={icon}
          name={toolLabel(name)}
          arg={open ? `${fileSummaries.length} 个文件` : ""}
          argNode={
            open ? undefined : (
              <JoinedFileChips
                files={fileSummaries}
                onOpenFile={
                  onOpenToolDiff
                    ? (index) => onOpenToolDiff(tool, chat, index)
                    : undefined
                }
              />
            )
          }
          animateSummary={status === "running"}
          secondary={
            firstFile && /[\\/]/.test(firstFile.path) ? (
              <span className="kimi-tool-row-secondary" title={firstFile.path}>
                {firstFile.path}
              </span>
            ) : undefined
          }
          // ZCode：多文件展开后隐藏总 diffCount，避免与各子卡的计数重复。
          chipNode={
            open || !totalStats ? undefined : (
              <ToolDiffCount added={totalStats.added} removed={totalStats.removed} />
            )
          }
          open={open}
          expandable
          stacked={stackPosition !== "single"}
          stackPosition={stackPosition}
          onToggle={() => {
            setUserToggled(true);
            setOpen((v) => !v);
          }}
        >
          <div className="kimi-tool-multi-edit-list">
            {fileSummaries.map((file, index) => (
              <EditFileSubCard
                key={`${file.path}:${index}`}
                file={file}
                kindLabel={toolLabel(name)}
                status={status}
                persistKey={editFilePersistKey(tool, fileSummaries, file, index)}
                streaming={isStreaming}
                onOpen={
                  onOpenToolDiff
                    ? () => onOpenToolDiff(tool, chat, index)
                    : undefined
                }
              />
            ))}
          </div>
        </ToolRow>
      </div>
    );
  }

  // ---- 单文件 ----
  const hasParameters = !isError && !singlePatch && Boolean(inputJson || arg.trim());
  /**
   * ZCode `diffToolStrategy` / `writeToolStrategy` 都是 `showOutput: Boolean(errorText)`：
   * **成功态不渲染 Result** —— 变更内容已经由 diff 表达，再摊一遍 toolResult 只是噪音
   * （现在 toolResult 里还带着整份 patch，渲染出来就是一坨 JSON）。
   * 只在失败时保留错误文本。
   */
  const showResultBlock = isError;
  const expandable = Boolean(singlePatch) || hasParameters || showResultBlock;
  const singleOpen = onOpenToolDiff ? () => onOpenToolDiff(tool, chat) : undefined;

  return (
    <ToolRow
      status={status}
      icon={icon}
      name={toolLabel(name)}
      arg={!open ? summary : ""}
      argNode={
        singlePath ? <ToolFileChip path={singlePath} onOpen={singleOpen} /> : undefined
      }
      secondary={
        // ZCode `renderFilePath`：文件名之后补一段父目录路径（裸文件名不重复渲染）
        singlePath && /[\\/]/.test(singlePath) ? (
          <span className="kimi-tool-row-secondary" title={singlePath}>
            {singlePath}
          </span>
        ) : undefined
      }
      statusTitle={isError && errorText ? errorText : undefined}
      animateSummary={status === "running"}
      chipNode={chipNode}
      open={open}
      expandable={expandable}
      stacked={stackPosition !== "single"}
      stackPosition={stackPosition}
      onToggle={() => {
        setUserToggled(true);
        setOpen((v) => !v);
      }}
    >
      {singlePatch ? (
        <EditInlineDiffContent
          patch={singlePatch}
          path={singlePath}
          title={name}
          streaming={isStreaming}
        />
      ) : null}
      {hasParameters ? (
        <ToolParametersBlock value={inputJson} text={inputJson ? undefined : arg} />
      ) : null}
      {showResultBlock ? (
        <ToolOutputBlock lines={output} errorText={errorText || undefined} />
      ) : null}
    </ToolRow>
  );
});

/** 子卡展开态的身份：优先用文件路径，路径重复时才追加下标兜底。 */
function editFilePersistKey(
  tool: CHAT.Task,
  files: EditFileSummary[],
  file: EditFileSummary,
  index: number
) {
  const duplicated = files.some(
    (other, otherIndex) => otherIndex !== index && other.path === file.path
  );
  return `${resolveToolKey(tool)}:${duplicated ? `${file.path}:${index}` : file.path}`;
}

/**
 * 多文件 edit 的主文本（对齐 ZCode `renderJoinedFileChips`）：
 * 多个文件名 chip 用 `,` 连接，每个 chip 各自打开对应文件的 diff 预览。
 */
function JoinedFileChips({
  files,
  onOpenFile,
}: {
  files: { path: string }[];
  onOpenFile?: (index: number) => void;
}) {
  return (
    <span className="kimi-tool-row-chips">
      {files.map((file, index) => (
        <span
          key={`${file.path}:${index}`}
          className="kimi-tool-row-chips-item"
        >
          {index > 0 ? <span className="kimi-tool-row-chip-sep">,</span> : null}
          <ToolFileChip
            path={file.path}
            onOpen={onOpenFile ? () => onOpenFile(index) : undefined}
          />
        </span>
      ))}
    </span>
  );
}

/**
 * 多文件 edit 的单个文件子卡（对齐 ZCode `EditFileSummaryBlock`）：
 * 无图标、类别标签 + 文件名 chip + 该文件自己的 `+A −B`，
 * 展开后是**这个文件**的 diff 预览 —— 不是整次调用的 diff。
 */
const EditFileSubCard = memo(function EditFileSubCard({
  file,
  kindLabel,
  status,
  persistKey,
  streaming,
  onOpen,
}: {
  file: EditFileSummary;
  kindLabel: string;
  status: "running" | "ok" | "error";
  persistKey?: string;
  streaming?: boolean;
  onOpen?: () => void;
}) {
  const [open, setOpen] = useToolCardOpen(persistKey ?? "", false);
  const expandable = Boolean(file.patch);

  return (
    <ToolRow
      status={status}
      name={kindLabel}
      arg=""
      argNode={<ToolFileChip path={file.path} onOpen={onOpen} />}
      chipNode={<ToolDiffCount added={file.added} removed={file.removed} />}
      open={open}
      expandable={expandable}
      onToggle={() => {
        if (expandable) setOpen((v) => !v);
      }}
    >
      {file.patch ? (
        <EditInlineDiffContent
          patch={file.patch}
          path={file.path}
          streaming={streaming}
        />
      ) : null}
    </ToolRow>
  );
});
