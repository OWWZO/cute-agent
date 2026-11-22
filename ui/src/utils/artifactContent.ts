/** 总结协议分隔符：`正文$$$ 产物路径`，正文与产物键由它切分；兼容行首 `$$`。 */
export const ARTIFACT_SECTION_DELIMITER = "$$$";

const ARTIFACT_KEY_SPLIT_PATTERN = /[、,\r\n，]+/;
const INVALID_ARTIFACT_PATH_PATTERN = /[\s*`?#<>|"'，,。；;：:！？!]/;
const ARTIFACT_BASENAME_PATTERN =
  /^(?:[^/\\]+\.[A-Za-z0-9][A-Za-z0-9._-]{0,15}|[A-Za-z0-9_.-]+)$/;
const TRAILING_DELIMITER_PATTERN = /(?:\r?\n[ \t]*\${2,3}[ \t]*)+\s*$/;
const DELIMITER_RUN_PATTERN = /\${2,3}(?:[ \t]*\r?\n[ \t]*\${2,3})*/g;
const DELIMITER_TOKEN_PATTERN = /\${2,3}/g;

export type ArtifactSection = {
  body: string;
  artifactKeys: string[];
};

export function normalizeArtifactKey(raw: string): string {
  let text = raw.trim();
  if (!text) {
    return "";
  }
  // 模型偶尔把分隔符重复输出（`正文$$$\n$$$ file.md`），残留的 $$$ 不是文件键。
  text = text.replace(DELIMITER_TOKEN_PATTERN, " ").trim();
  text = text.replace(/^[-*+]\s+/, "").replace(/^\d+[.)]\s+/, "").trim();
  text = text.replace(/^[*`]+|[`*]+$/g, "").trim();
  const markdownLink = text.match(/^\[[^\]]*]\(([^)]+)\)$/);
  if (markdownLink?.[1]) {
    text = markdownLink[1].trim();
  }
  text = text.replace(/^['"]+|['"]+$/g, "").trim();
  return text.replace(/[。.;]+$/g, "").trim();
}

export function splitArtifactKeys(value: string): string[] {
  return value
    .split(ARTIFACT_KEY_SPLIT_PATTERN)
    .map(normalizeArtifactKey)
    .filter(Boolean);
}

function isPlausibleArtifactKey(key: string): boolean {
  if (
    !key ||
    key.length > 512 ||
    key.includes("$$") ||
    /[\\/]$/.test(key) ||
    INVALID_ARTIFACT_PATH_PATTERN.test(key)
  ) {
    return false;
  }
  const basename = key.split(/[\\/]/).pop() || "";
  return ARTIFACT_BASENAME_PATTERN.test(basename);
}

/**
 * 从终答中找最后一个合法的交付文件段。
 *
 * 正文中可能只是提到 `$$$`，因此不能见到分隔符就切；分隔符后面的内容
 * 必须整体像文件路径列表。倒序查找也能跳过可能存在的结尾分隔符。
 */
export function extractArtifactSection(value: string): ArtifactSection | null {
  const candidates = [...value.matchAll(DELIMITER_RUN_PATTERN)].map((match) => ({
    start: match.index,
    end: match.index + match[0].length,
    marker: match[0],
  }));

  for (let index = candidates.length - 1; index >= 0; index -= 1) {
    const candidate = candidates[index];
    if (!isDoubleDollarDelimiterAllowed(value, candidate.start)) {
      continue;
    }
    let suffix = value.slice(candidate.end).trim();
    suffix = suffix.replace(TRAILING_DELIMITER_PATTERN, "").trim();

    if (!suffix) {
      if (
        !candidate.marker.includes("\n") &&
        !isDelimiterLine(value, candidate.start, candidate.end)
      ) {
        continue;
      }
      if (index > 0) {
        continue;
      }
      return {
        body: value.slice(0, candidate.start).trim(),
        artifactKeys: [],
      };
    }

    const artifactKeys = splitArtifactKeys(suffix);
    if (
      artifactKeys.length &&
      artifactKeys.every(isPlausibleArtifactKey)
    ) {
      return {
        body: value.slice(0, candidate.start).trim(),
        artifactKeys,
      };
    }
  }
  return null;
}

function isDoubleDollarDelimiterAllowed(
  value: string,
  start: number
) {
  if (value.slice(start, start + 3) === "$$$") {
    return true;
  }
  const lineStart = value.lastIndexOf("\n", start - 1) + 1;
  return !value.slice(lineStart, start).trim();
}

function isDelimiterLine(value: string, start: number, end: number) {
  const lineStart = value.lastIndexOf("\n", start - 1) + 1;
  const lineEnd = value.indexOf("\n", end);
  return (
    !value.slice(lineStart, start).trim() &&
    !value.slice(end, lineEnd === -1 ? value.length : lineEnd).trim()
  );
}

export function stripArtifactSection(value: string): string {
  return extractArtifactSection(value)?.body ?? value;
}
