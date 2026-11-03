import { buildDefaultToolBaseUrl } from "@/pages/WorkspaceImageGeneration/utils";

import type { SopEditorDraft, SopItem, SopStatus, SopStep } from "./types";

export const SOP_WORKSPACE_STORAGE_KEY = "workspace-sop:state";
export const DEFAULT_SOP_TYPE = "list";
export const DEFAULT_SOP_PAGE_SIZE = 200;

export function resolveToolBaseUrl(): string {
  return buildDefaultToolBaseUrl();
}

export function createEmptyStep(): SopStep {
  return { title: "", steps: [""] };
}

export function createEmptyDraft(): SopEditorDraft {
  return {
    sopId: null,
    sopName: "",
    sopDesc: "",
    sopType: DEFAULT_SOP_TYPE,
    sopSteps: [createEmptyStep()],
    status: "online",
  };
}

export function sopItemToDraft(item: SopItem): SopEditorDraft {
  // 编辑草稿始终至少保留一个步骤和一个空子项，避免表单出现不可输入的空数组。
  return {
    sopId: item.sopId,
    sopName: item.sopName,
    sopDesc: item.sopDesc,
    sopType: item.sopType || DEFAULT_SOP_TYPE,
    sopSteps:
      item.sopSteps && item.sopSteps.length > 0
        ? item.sopSteps.map((step) => ({
            title: step.title || "",
            steps: step.steps && step.steps.length > 0 ? [...step.steps] : [""],
          }))
        : [createEmptyStep()],
    status: item.status || "online",
  };
}

export function normalizeStatus(value: unknown): SopStatus {
  // 只接受已知状态，其它后端值按在线处理，保证列表展示有安全默认值。
  const status = String(value || "online").toLowerCase();
  if (status === "offline" || status === "draft") {
    return status;
  }
  return "online";
}

export function statusLabel(status: SopStatus): string {
  if (status === "online") return "已上线";
  if (status === "offline") return "已下线";
  return "草稿";
}
