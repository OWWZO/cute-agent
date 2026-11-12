import { useEffect, useRef, type Dispatch, type SetStateAction } from "react";

import { draftsEqual, shouldSyncDraft } from "./draftSync";

export type Draft = { isNew: boolean };

type SyncSnapshot<TDraft extends Draft> = {
  key: string;
  draft: TDraft;
};

/** 每次从服务端快照派生 draft 时都会生成新对象；同步判定必须看内容，不能看引用。 */
export function syncSelectedDraft<TDraft extends Draft>(
  draft: TDraft,
  setDraft: Dispatch<SetStateAction<TDraft>>,
  lastSyncedRef: { current: SyncSnapshot<TDraft> | null },
  selectedKey: string,
  nextDraft: TDraft,
): void {
  const lastSynced = lastSyncedRef.current;
  if (!shouldSyncDraft(draft, lastSynced?.draft, selectedKey !== lastSynced?.key)) {
    return;
  }
  if (draftsEqual(draft, nextDraft)) {
    // 点选时表单已由事件写入；仍需记住本次选中项，避免下次刷新被误判为切换。
    lastSyncedRef.current = { key: selectedKey, draft: nextDraft };
    return;
  }
  lastSyncedRef.current = { key: selectedKey, draft: nextDraft };
  setDraft(nextDraft);
}

/** 选中项和列表变化才触发同步；draft 只经 ref 读取，避免 setDraft 反过来触发 effect。 */
export function useSelectedDraftSync<TDraft extends Draft, TRecord>(
  selectedKey: string | null,
  items: TRecord[],
  getKey: (record: TRecord) => string,
  recordToDraft: (record: TRecord) => TDraft,
  draft: TDraft,
  setDraft: Dispatch<SetStateAction<TDraft>>,
): void {
  const lastSyncedRef = useRef<SyncSnapshot<TDraft> | null>(null);
  const draftRef = useRef(draft);
  draftRef.current = draft;

  useEffect(() => {
    if (!selectedKey) {
      lastSyncedRef.current = null;
      return;
    }
    const hit = items.find((item) => getKey(item) === selectedKey);
    if (!hit) {
      return;
    }
    syncSelectedDraft(
      draftRef.current,
      setDraft,
      lastSyncedRef,
      selectedKey,
      recordToDraft(hit),
    );
  }, [items, selectedKey, getKey, recordToDraft, setDraft]);
}
