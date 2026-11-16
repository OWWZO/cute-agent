import { basenameOfPath, getTaskFiles, normalizeRelativePath } from "@/utils/taskArtifacts";
import {
  extractArtifactSection,
  normalizeArtifactKey,
  splitArtifactKeys,
  stripArtifactSection,
} from "@/utils/artifactContent";

function asText(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function resolveSummaryRecords(task?: CHAT.Task) {
  const taskRecord = (task || {}) as unknown as Record<string, unknown>;
  const resultMapRecord = (task?.resultMap || {}) as Record<string, unknown>;
  return { taskRecord, resultMapRecord };
}

function collectSummaryProtocolTexts(task?: CHAT.Task): string[] {
  if (!task) {
    return [];
  }
  const { taskRecord, resultMapRecord } = resolveSummaryRecords(task);
  return [
    asText(resultMapRecord.taskSummary),
    asText(taskRecord.taskSummary),
    asText(task.result),
    asText(resultMapRecord.result),
  ].filter(Boolean);
}

function resolveTaskSummarySourceText(task?: CHAT.Task) {
  return collectSummaryProtocolTexts(task)[0] || "";
}

/**
 * 时间线任务总结和最终结论共用同一套文本回退顺序，
 * 这样可以避免不同展示区各自兜底时出现文案不一致。
 */
export function resolveTaskSummaryText(task?: CHAT.Task) {
  const rawText = resolveTaskSummarySourceText(task);
  return stripArtifactSection(rawText);
}

function parseArtifactKeys(value: unknown): string[] {
  if (typeof value === "string") {
    return splitArtifactKeys(value);
  }
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => normalizeArtifactKey(typeof item === "string" ? item : ""))
    .filter(Boolean);
}

function readStoredArtifactKeys(task?: CHAT.Task): string[] {
  if (!task) {
    return [];
  }
  const { taskRecord, resultMapRecord } = resolveSummaryRecords(task);
  const fromMap = parseArtifactKeys(resultMapRecord.artifactKeys);
  if (fromMap.length) {
    return fromMap;
  }
  return parseArtifactKeys(taskRecord.artifactKeys);
}

function parseProtocolArtifactKeys(text: string): string[] {
  return extractArtifactSection(text)?.artifactKeys ?? [];
}

export function resolveTaskSummaryArtifactKeys(task?: CHAT.Task): string[] {
  for (const text of collectSummaryProtocolTexts(task)) {
    const keys = parseProtocolArtifactKeys(text);
    if (keys.length) {
      return keys;
    }
  }
  return readStoredArtifactKeys(task);
}

function workspacePathOf(file: CHAT.TFile) {
  return normalizeRelativePath(
    file.relativePath || file.originFileName || file.name
  ).toLowerCase();
}

function pickFileByWorkspacePath(files: CHAT.TFile[], key: string, used: Set<number>) {
  const needle = normalizeRelativePath(key).toLowerCase();
  if (!needle) {
    return -1;
  }
  const exact = files.findIndex(
    (file, index) => !used.has(index) && workspacePathOf(file) === needle
  );
  if (exact >= 0) {
    return exact;
  }
  const base = basenameOfPath(needle).toLowerCase();
  const matches = files
    .map((file, index) => ({ file, index }))
    .filter(
      ({ file, index }) =>
        !used.has(index) && basenameOfPath(workspacePathOf(file)) === base
    );
  return matches.length === 1 ? matches[0].index : -1;
}

export function isSameDeliveryFile(left: CHAT.TFile, right: CHAT.TFile) {
  const leftKey = String(left.resourceKey || "").toLowerCase();
  const rightKey = String(right.resourceKey || "").toLowerCase();
  if (leftKey && rightKey && leftKey === rightKey) {
    return true;
  }
  const leftPath = String(
    left.relativePath || left.originFileName || left.name || ""
  )
    .replace(/\\/g, "/")
    .toLowerCase();
  const rightPath = String(
    right.relativePath || right.originFileName || right.name || ""
  )
    .replace(/\\/g, "/")
    .toLowerCase();
  if (leftPath && rightPath && leftPath === rightPath) {
    return true;
  }
  if (left.url && right.url && left.url === right.url) {
    return true;
  }
  if (left.downloadUrl && right.downloadUrl && left.downloadUrl === right.downloadUrl) {
    return true;
  }
  return false;
}

function pickFilesByArtifactKeys(files: CHAT.TFile[], keys: string[]) {
  const used = new Set<number>();
  const featured: CHAT.TFile[] = [];
  for (const key of keys) {
    const index = pickFileByWorkspacePath(files, key, used);
    if (index >= 0) {
      used.add(index);
      featured.push(files[index]);
    }
  }
  return featured;
}

function mergeDeliveryFiles(task?: CHAT.Task, sessionFiles?: CHAT.TFile[]) {
  const merged: CHAT.TFile[] = [];
  for (const file of [...getTaskFiles(task), ...(sessionFiles || [])]) {
    if (!merged.some((item) => isSameDeliveryFile(item, file))) {
      merged.push(file);
    }
  }
  return merged;
}

export function pickFeaturedDeliveryFiles(
  task?: CHAT.Task,
  sessionFiles?: CHAT.TFile[]
): CHAT.TFile[] {
  const keys = resolveTaskSummaryArtifactKeys(task);
  if (!keys.length) {
    return [];
  }
  return pickFilesByArtifactKeys(mergeDeliveryFiles(task, sessionFiles), keys);
}

export function shouldShowWorkspaceFilesEntry(
  featured: CHAT.TFile[],
  task?: CHAT.Task,
  sessionFiles?: CHAT.TFile[]
) {
  const candidates = [...getTaskFiles(task), ...(sessionFiles || [])];
  return candidates.some(
    (candidate) =>
      !featured.some((file) => isSameDeliveryFile(file, candidate))
  );
}
