const DEFAULT_THINKING_EFFORT = "high";

/**
 * 组装发送载荷。
 * 标准 Agent 透传深度思考、文件、模型和计划模式等字段；dataAgent 仍由独立问数入口处理。
 */
export function buildSubmitPayload(params: {
  question: string;
  collaborationMode: "normal" | "cluster";
  agentKey?: string | null;
  isDataAgent: boolean;
  uploadedFiles: CHAT.TFile[];
  model?: string;
  thinking?: boolean;
  thinkingEffort?: string | null;
  forcePlanMode?: boolean;
}) {
  const model = params.model?.trim() || undefined;
  const thinking = params.thinking;
  const thinkingEffort = params.thinking
    ? params.thinkingEffort?.trim() || DEFAULT_THINKING_EFFORT
    : undefined;
  // 两个分支共用的字段只在这里拼一次，避免后续新增字段时漏改其中一支。
  const base = {
    message: params.question.trim(),
    files: params.uploadedFiles.length > 0 ? params.uploadedFiles : undefined,
    model,
    thinking,
    thinkingEffort,
  };

  if (params.isDataAgent) {
    return {
      ...base,
      outputStyle: "dataAgent" as const,
      collaborationMode: "normal" as const,
    };
  }

  return {
    ...base,
    collaborationMode: params.collaborationMode,
    ...(params.collaborationMode === "normal" && params.agentKey?.trim()
      ? { agentKey: params.agentKey.trim() }
      : {}),
    ...(params.forcePlanMode ? { forcePlanMode: true } : {}),
  };
}
