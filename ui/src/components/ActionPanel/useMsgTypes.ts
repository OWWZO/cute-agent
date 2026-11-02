import { isHTML } from "@/utils";
import { isSubAgentParentTask } from "@/utils/chat/subagent";
import {
  buildDeepSearchResultItems,
  resolveDeepSearchStage,
} from "@/utils/deepSearch";
import {
  resolveTaskResultMap,
  resolveTaskToolResult,
  resolveTaskToolResultText,
  type ResolvedToolResult,
} from "@/utils/chat/toolCalls";
import { useMemo } from "react";
import { PanelItemType, SearchListItem } from "./type";
import {
  getPrimaryTaskFile,
  isDocxFileLike,
  isExcelFileLike,
  isLegacyDocFileLike,
  isPdfFileLike,
  isPptFileLike,
} from "@/utils/taskArtifacts";
import { resolveFilePreviewKind } from "@/lib/filePreview/kinds";
import { normalizeToolName } from "@/components/Dialogue/tools/toolMeta";

export type ParsedTaskResult = {
  resultMap: MESSAGE.ResultMap;
  toolResult?: ResolvedToolResult;
  toolResultText: string;
  parsedToolResult?: unknown;
  isToolResultJsonValid: boolean;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** 搜索结果可能本身就是数组，也可能包在 data / hits / results 字段里。 */
function pickSearchRows(value: unknown): unknown[] {
  if (Array.isArray(value)) {
    return value;
  }
  if (!isRecord(value)) {
    return [];
  }
  const candidates: unknown[] = [value.data, value.hits, value.results];
  for (const candidate of candidates) {
    if (Array.isArray(candidate)) {
      return candidate;
    }
  }
  return [];
}

export const getSearchList = (
  taskItem?: PanelItemType,
  resolved?: ParsedTaskResult
) => {
  if (!taskItem) {
    return [];
  }
  const { messageType } = taskItem;
  const resultMap = resolved?.resultMap ?? resolveTaskResultMap(taskItem);

  const toolResult = resolved?.toolResult ?? resolveTaskToolResult(taskItem);
  const toolName = normalizeToolName(String(
    toolResult?.toolName ||
    (typeof resultMap.toolName === "string" ? resultMap.toolName : "")
  ));
  if (messageType === 'tool_result') {
    if (toolName === 'search') {
      const resultText =
        resolved?.toolResultText ?? resolveTaskToolResultText(taskItem, toolResult);
      let tool: unknown = resolved?.parsedToolResult;
      if (!resolved) {
        try {
          tool = JSON.parse(resultText || "{}");
        } catch {
          // 工具输出不是合法 JSON：视为没有结构化搜索结果，回退到空列表。
          return [];
        }
      }
      const list = pickSearchRows(tool);
      return list.length
        ? list.map((item) => {
          const row =
            item && typeof item === "object"
              ? (item as Record<string, unknown>)
              : {};
          return {
            name: String(row.pageName || row.name || row.title || ""),
            pageContent: String(
              row.pageContent ||
                row.page_content ||
                row.snippet ||
                row.content ||
                ""
            ),
            url: String(
              row.sourceUrl ||
                row.source_url ||
                row.url ||
                row.link ||
                ""
            ),
          };
        })
        : [];
    }
    return [];
  }
  if (messageType === 'knowledge') {
    const list = resultMap?.refList || [];
    return list.map(item => ({
      name: item.name,
      pageContent: item.pageContent,
      url: item.sourceUrl
    }));
  }
  if (messageType === "deep_search") {
    const stage = resolveDeepSearchStage(resultMap?.messageType);
    if (stage === "search" || stage === "chapter_summary") {
      return buildDeepSearchResultItems(resultMap?.searchResult?.docs) as SearchListItem[];
    }
  }
  return [];
};

export const useMsgTypes = (taskItem?: PanelItemType) => {
  const parsedTask = useMemo(() => {
    if (!taskItem) return null;
    const resultMap = resolveTaskResultMap(taskItem);
    const toolResult = resolveTaskToolResult(taskItem);
    const toolResultText = resolveTaskToolResultText(taskItem, toolResult);
    let parsedToolResult: unknown;
    let isToolResultJsonValid = false;
    if (taskItem.messageType === "tool_result" && toolResultText) {
      try {
        parsedToolResult = JSON.parse(toolResultText);
        isToolResultJsonValid = true;
      } catch {
        // 非法 JSON 保持 isToolResultJsonValid=false，调用方回退到文本渲染。
        parsedToolResult = undefined;
      }
    }
    return {
      resultMap,
      toolResult,
      toolResultText,
      parsedToolResult,
      isToolResultJsonValid,
    } satisfies ParsedTaskResult;
  }, [taskItem]);

  const searchList = useMemo<SearchListItem[]>(() => {
    return getSearchList(taskItem, parsedTask || undefined);
  }, [taskItem, parsedTask]);

  return useMemo(() => {
    if (!taskItem || !parsedTask) {
      return;
    }
    const { messageType } = taskItem;
    const { resultMap, toolResult, toolResultText } = parsedTask;
    const rawToolName = String(toolResult?.toolName || "").toLowerCase();
    const primaryFile = getPrimaryTaskFile(taskItem);
    // 类型判定统一走 resolveFilePreviewKind，不再各自维护扩展名表。
    const primaryKind = primaryFile
      ? resolveFilePreviewKind({
        name: primaryFile.name,
        mimeType: primaryFile.mimeType,
        url: primaryFile.url,
      })
      : null;
    const useExcel = !!primaryFile && isExcelFileLike(primaryFile);
    const usePdf = !!primaryFile && isPdfFileLike(primaryFile);
    const useDocx = !!primaryFile && isDocxFileLike(primaryFile);
    const useLegacyDoc = !!primaryFile && isLegacyDocFileLike(primaryFile);
    const useWord = useDocx || useLegacyDoc;

    let isHtml = false;
    if (messageType === 'code' && resultMap.codeOutput) {
      isHtml = isHTML(resultMap.codeOutput);
    } else if (messageType === 'tool_result' && rawToolName === 'code_interpreter' && toolResultText) {
      isHtml = isHTML(toolResultText);
    }
    const useHtml = messageType === 'html' || primaryKind === 'html';
    const usePpt = messageType === 'ppt' || (!!primaryFile && isPptFileLike(primaryFile));
    // 只要主文件是图片就走图片预览（图表/工具产物常见为 tool_result + .png）。
    // 注意 SVG 不算位图：它要「图形 / 源码」双模式，走 useSvg。
    const useImage = primaryKind === "image";
    const useSvg = primaryKind === "svg";
    const mediaKind: "video" | "audio" | null =
      primaryKind === "video" ? "video" : primaryKind === "audio" ? "audio" : null;
    const useMedia = mediaKind !== null;
    const useFile =
      !!primaryFile &&
      !useImage &&
      !useSvg &&
      !useMedia &&
      !useExcel &&
      !useHtml &&
      !usePpt &&
      !usePdf &&
      !useWord;
    const useCode = messageType === 'code' && !useFile;

    return {
      useBrowser: messageType === 'browser' || messageType === 'browser_viewport',
      useCode,
      // 供 ActionPanel / panelResolver 复用，避免同一次渲染重复解析 resultMap / toolResult。
      resultMap,
      toolResult,
      toolResultText,
      useHtml,
      useImage,
      useSvg,
      useMedia,
      mediaKind,
      useExcel,
      usePdf,
      useDocx,
      useLegacyDoc,
      useWord,
      useFile,
      // Agent 观察值可能是 JSON，但仍走 SubAgent markdown，不能当成空白 Structured data。
      useJSON:
        messageType === "tool_result" &&
        !!toolResultText &&
        parsedTask.isToolResultJsonValid &&
        !isSubAgentParentTask(taskItem),
      isHtml,
      searchList,
      usePpt
    };
  }, [parsedTask, searchList, taskItem]);
};
