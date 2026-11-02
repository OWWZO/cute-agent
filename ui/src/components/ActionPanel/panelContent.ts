import { buildDeepSearchExtendMarkdown, resolveDeepSearchStage } from "@/utils/deepSearch";
import { buildSubAgentMarkdown, isSubAgentParentTask } from "@/utils/chat/subagent";
import {
  resolveTaskResultMap,
  resolveTaskToolResultText,
} from "@/utils/chat/toolCalls";
import { PanelItemType } from "./type";
import type { ParsedTaskResult } from "./useMsgTypes";

type ResolvedTaskContent = Pick<
  ParsedTaskResult,
  "resultMap" | "toolResult" | "toolResultText"
>;

function buildToolCallMarkdown(resultMap?: PanelItemType["resultMap"]) {
  if (!resultMap) {
    return "";
  }

  const contentBlocks: string[] = [];
  const summary = typeof resultMap.summary === "string" ? resultMap.summary.trim() : "";
  if (summary) {
    contentBlocks.push(summary);
  } else if (resultMap.toolName) {
    contentBlocks.push(`正在调用 \`${resultMap.toolName}\``);
  }

  // 工具入参（Structured data）暂不展示，仅保留调用摘要。
  return contentBlocks.join("\n\n");
}

export const resolveMarkdownContent = (
  taskItem?: PanelItemType,
  resolved?: ResolvedTaskContent
) => {
  let markDownContent = "";

  if (!taskItem) {
    return markDownContent;
  }

  const { messageType } = taskItem;

  // 子智能体父任务（tool_call / tool_result）统一走 SubAgent markdown，
  // 不再在两个 case 里各写一份。
  if (
    (messageType === "tool_call" || messageType === "tool_result") &&
    isSubAgentParentTask(taskItem)
  ) {
    return buildSubAgentMarkdown(taskItem);
  }

  const resultMap = resolved?.resultMap ?? resolveTaskResultMap(taskItem);
  const toolResultText =
    resolved?.toolResultText ??
    resolveTaskToolResultText(taskItem, resolved?.toolResult);

  switch (messageType) {
    case "tool_result":
      markDownContent = toolResultText;
      break;
    case "llm_reasoning":
      markDownContent =
        taskItem.reasoningContent ||
        resultMap?.reasoningContent ||
        taskItem.toolThought ||
        "";
      break;
    case "tool_thought":
      // 助手过程回复，不是思考
      markDownContent = taskItem.toolThought || "";
      break;
    case "tool_call":
      markDownContent = buildToolCallMarkdown(resultMap);
      break;
    case "code":
      // 细粒度过程区（任务/思考/代码/执行输出）落在 codeOutput；仅有 code 时兜底为
      // 无语言标记的代码块 —— code 事件也用于非 Python 内容，硬编码 python 会误高亮。
      if (resultMap?.codeOutput) {
        markDownContent = resultMap.codeOutput;
      } else if (resultMap?.code) {
        markDownContent = `\`\`\`\n${resultMap.code}\n\`\`\``;
      }
      break;
    case "markdown":
    case "html":
    case "data_analysis":
      markDownContent = resultMap?.codeOutput || "";
      break;
    case "deep_search":
    case "report":
      // 查询分解阶段还没有搜索结果文档，用 Markdown 先把待检索子查询展示出来。
      if (
        messageType === "deep_search" &&
        resolveDeepSearchStage(resultMap?.messageType) === "extend" &&
        !resultMap?.answer
      ) {
        markDownContent = buildDeepSearchExtendMarkdown(resultMap?.searchResult?.query);
        break;
      }
      markDownContent = resultMap.answer || "";
      break;
  }

  return markDownContent;
};

const resolvePanelContent = (
  taskItem?: PanelItemType,
  resolved?: ResolvedTaskContent
) => ({ markDownContent: resolveMarkdownContent(taskItem, resolved) });

export default resolvePanelContent;
