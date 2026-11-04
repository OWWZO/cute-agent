export function draftsEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function shouldSyncDraft(
  current: unknown,
  lastSynced: unknown,
  selectedKeyChanged: boolean
): boolean {
  return (
    selectedKeyChanged ||
    lastSynced === null ||
    lastSynced === undefined ||
    draftsEqual(current, lastSynced)
  );
}
