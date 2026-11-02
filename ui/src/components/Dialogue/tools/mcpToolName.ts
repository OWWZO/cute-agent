/**
 * MCP 工具名解析（对齐后端 `McpToolNamePolicy`：`mcp__{server}__{tool}`）。
 *
 * 注意：**不要**用 `normalizeToolName` —— 它会把名字小写化，而 server / tool 段
 * 来自 MCP 服务端原始标识，大小写有意义（且后端只做了非 `[a-zA-Z0-9_-]` → `_` 的替换）。
 */
export const MCP_TOOL_PREFIX = "mcp__";
export const MCP_TOOL_SEPARATOR = "__";

export type McpToolIdentity = {
  /** FQ 名（原始大小写） */
  qualifiedName: string;
  serverKey: string;
  toolName: string;
};

type McpToolSeparatorHit = {
  body: string;
  index: number;
};

/**
 * 找到 `mcp__{server}__{tool}` 里分隔 server / tool 的那个 `__`。
 *
 * 前缀缺失、server 段为空（`idx === 0`）或 tool 段为空时返回 null。
 * `isMcpToolName` 与 `parseMcpToolName` 共用这一条边界规则，避免两处判定漂移。
 */
function findMcpToolSeparator(
  name: string | null | undefined
): McpToolSeparatorHit | null {
  if (!name || !name.startsWith(MCP_TOOL_PREFIX)) return null;
  const body = name.slice(MCP_TOOL_PREFIX.length);
  const index = body.indexOf(MCP_TOOL_SEPARATOR);
  if (index <= 0 || index >= body.length - MCP_TOOL_SEPARATOR.length) {
    return null;
  }
  return {
    body,
    index,
  };
}

/** 是否是 MCP 的 FQ 工具名。类型守卫：命中后 `name` 收窄为 `string`。 */
export function isMcpToolName(
  name: string | null | undefined
): name is string {
  return findMcpToolSeparator(name) !== null;
}

/**
 * 解析 MCP FQ 名。非 FQ 返回 null。
 *
 * server 段可能自身含 `_`（后端把非白名单字符换成 `_`），因此只能按**第一个**
 * `__` 切分 —— 不能 split 后取 [1]/[2]。
 */
export function parseMcpToolName(
  name: string | null | undefined
): McpToolIdentity | null {
  const hit = findMcpToolSeparator(name);
  if (!hit || !name) return null;
  return {
    qualifiedName: name,
    serverKey: hit.body.slice(0, hit.index),
    toolName: hit.body.slice(hit.index + MCP_TOOL_SEPARATOR.length),
  };
}

/** 结果是否短到可以当一句话展示（ZCode `isCompactResult`）。 */
const COMPACT_RESULT_MAX_LENGTH = 160;

export function looksLikeJson(value: string): boolean {
  const trimmed = value.trim();
  if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
  try {
    JSON.parse(trimmed);
    return true;
  } catch {
    return false;
  }
}

export function isCompactMcpResult(value: string): boolean {
  const trimmed = value.trim();
  return (
    trimmed.length > 0 &&
    trimmed.length <= COMPACT_RESULT_MAX_LENGTH &&
    !trimmed.includes("\n") &&
    !looksLikeJson(trimmed)
  );
}
