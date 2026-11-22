import { normalizeTaskFile } from "@/utils/taskArtifacts";

type RecordLike = Record<string, unknown>;

export type WorkspaceFileEvent = {
  files: CHAT.TFile[];
  workspaceOnly: boolean;
  rootSessionId: string;
};

function isRecord(value: unknown): value is RecordLike {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function nestedMaps(eventData: unknown): RecordLike[] {
  const maps: RecordLike[] = [];
  let current: unknown = eventData;
  for (let depth = 0; depth < 5 && isRecord(current); depth += 1) {
    maps.push(current);
    current = current.resultMap;
  }
  return maps;
}

function firstText(maps: RecordLike[], key: string): string {
  for (const map of maps) {
    const value = map[key];
    if (value != null && String(value).trim()) {
      return String(value).trim();
    }
  }
  return "";
}

function firstArray(maps: RecordLike[], key: string): unknown[] {
  for (const map of maps) {
    const value = map[key];
    if (Array.isArray(value) && value.length) {
      return value;
    }
  }
  return [];
}

function workspaceFileKey(file: CHAT.TFile): string {
  return (
    file.relativePath ||
    file.resourceKey ||
    file.previewUrl ||
    file.downloadUrl ||
    file.name ||
    ""
  );
}

/**
 * Reads a file event as workspace metadata.
 *
 * The root stream receives both main-agent file events and workspace-only
 * relays from sub-agents. The latter must not be merged into the main chat
 * timeline as a normal task.
 */
export function readWorkspaceFileEvent(
  eventData?: MESSAGE.EventData | null
): WorkspaceFileEvent | null {
  if (!eventData) {
    return null;
  }
  const maps = nestedMaps(eventData);
  if (!maps.some((map) => String(map.messageType || "").trim() === "file")) {
    return null;
  }

  const workspaceOnly = maps.some((map) => map.workspaceOnly === true);
  const rootSessionId =
    firstText(maps, "rootSessionId") || firstText(maps, "sessionId");
  const rawFiles = firstArray(maps, "fileInfo");
  const candidates = rawFiles.length ? rawFiles : firstArray(maps, "artifactRefs");
  const files: CHAT.TFile[] = [];
  const seen = new Set<string>();
  for (const raw of candidates) {
    if (!isRecord(raw)) {
      continue;
    }
    const file = normalizeTaskFile(raw, { sessionId: rootSessionId });
    const key = file ? workspaceFileKey(file) : "";
    if (!file || !key || seen.has(key)) {
      continue;
    }
    seen.add(key);
    files.push(file);
  }
  return { files, workspaceOnly, rootSessionId };
}

/** Upserts by stable file identity; a later write replaces stale URL/size metadata. */
export function mergeWorkspaceFiles(
  current: CHAT.TFile[],
  incoming: CHAT.TFile[]
): CHAT.TFile[] {
  if (!incoming.length) {
    return current;
  }
  const merged = new Map<string, CHAT.TFile>();
  for (const file of current) {
    const key = workspaceFileKey(file);
    if (key) {
      merged.set(key, file);
    }
  }
  for (const file of incoming) {
    const key = workspaceFileKey(file);
    if (!key) {
      continue;
    }
    merged.set(key, { ...(merged.get(key) || {}), ...file });
  }
  return [...merged.values()];
}
