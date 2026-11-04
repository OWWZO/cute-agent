import { describe, expect, it } from "vitest";

import { shouldSyncDraft } from "./draftSync";

describe("shouldSyncDraft", () => {
  const baseDraft = {
    agentKey: "researcher",
    systemPrompt: "原始提示词",
  };

  it("切换选中项时同步服务端快照", () => {
    expect(shouldSyncDraft({ ...baseDraft, systemPrompt: "用户编辑" }, baseDraft, true)).toBe(true);
  });

  it("同一选中项刷新列表时不覆盖未保存编辑", () => {
    expect(shouldSyncDraft({ ...baseDraft, systemPrompt: "用户编辑" }, baseDraft, false)).toBe(
      false
    );
  });

  it("草稿未修改时允许服务端外部更新回填", () => {
    expect(shouldSyncDraft(baseDraft, baseDraft, false)).toBe(true);
  });
});
