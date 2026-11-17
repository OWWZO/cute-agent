function isPreviewable(file: CHAT.TFile): boolean {
  return Boolean(
    !file.missing &&
      (file.previewUrl || file.downloadUrl || file.url)
  );
}

/**
 * 选最新一轮的最终展示产物。
 *
 * 文件接口按创建时间升序返回，所以同一轮里取最后一个可预览的输出 artifact。
 * 新接口会带 requestId；旧缓存没有该字段时退回整个清单的最后一个可预览文件。
 */
export function selectLatestTurnPreviewFile(
  files: CHAT.TFile[],
  requestIds: string[]
): CHAT.TFile | undefined {
  const previewable = files.filter(isPreviewable);
  if (!previewable.length) {
    return undefined;
  }

  const latestIds = new Set(requestIds.filter(Boolean));
  if (latestIds.size && previewable.some((file) => file.requestId)) {
    const inLatestTurn = previewable.filter(
      (file) => file.requestId && latestIds.has(file.requestId)
    );
    const outputs = inLatestTurn.filter(
      (file) => file.artifactRole !== "input"
    );
    const matched = (outputs.length ? outputs : inLatestTurn).at(-1);
    if (matched) {
      return matched;
    }

    // 异步子 Agent 的 artifact.requestId 是 sub:*，不等于父 turn 的 requestId。
    // 文件清单按创建时间升序，仍取最后一个子 Agent 输出作为该轮展示产物。
    return previewable
      .filter(
        (file) =>
          file.requestId?.startsWith("sub:") &&
          file.artifactRole !== "input"
      )
      .at(-1);
  }

  return previewable.at(-1);
}
