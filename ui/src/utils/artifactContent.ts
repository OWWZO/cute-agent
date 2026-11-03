/** 总结协议分隔符：`正文$$$ 产物路径`，正文与产物键由它切分。 */
export const ARTIFACT_SECTION_DELIMITER = "$$$";

export function stripArtifactSection(value: string): string {
  const delimiterIndex = value.indexOf(ARTIFACT_SECTION_DELIMITER);
  return delimiterIndex >= 0
    ? value.slice(0, delimiterIndex).trim()
    : value;
}
