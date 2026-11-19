/**
 * 程序体本地纠偏：模型写歪的 DSL，在浏览器里就地掰正。
 *
 * <p>与 `segments.ts` 里的 `normalizeOpenUiFences` / `normalizeOpenUiMarkers` 是同一类东西——
 * 提示词已经写明了正确写法，模型还是会自创形态。差别是那两个修的是 marker 外壳，
 * 这个修的是程序体内部。</p>
 *
 * <p>动机是成本：纠偏命中就不需要打 Autofix（按次计费、不支持流式，且每次都要带上
 * 53KB 的组件库 config 块）。像 `@//set/(` 这种错误，一次字符串替换就够了。</p>
 *
 * <p>五条规则，都只做**往合法枚举/写法里收**的替换，不做语义改写：</p>
 * <ol>
 *   <li>action 写成了路径形态 `@//set/(` → `@Set(`；</li>
 *   <li>内置函数写成了路径形态 `@//clamp/(` → `@Clamp(`（模型常把 `@Clamp` / `@Count`
 *       和 action 混成同一种路径记法——函数比 action 更常见，实战里两者会同时出现）；</li>
 *   <li>`Text` 用了只有 `Title` 才支持的字号 → 钳到 `Text` 的取值上界 `lg`。</li>
 *   <li>对象字面量的中文键名没加引号 → 补双引号。解析器对此不报错，
 *       但会静默丢掉字段，最终把一行解析成 `{"?": 最后一个值}`。</li>
 *   <li>中文成员访问写成 `last.出生率` → `last["出生率"]`。解析器会把点号后的
 *       中文字段解析成 `?`，进而把后续参数整体错位。</li>
 * </ol>
 *
 * <p>两者都是有损的（第 3 条会改小字号），所以调用方必须用「原文不可渲染 + 纠偏后可渲染」
 * 才采用的策略：纠不动就回退原文继续走 Autofix，绝不会把本就能渲染的程序改坏。</p>
 */

/** 已知 action 步骤的规范名（小写 → 规范写法）。与 `prompt-resident.txt` 的 Action 段一致。 */
const ACTION_STEPS: Record<string, string> = {
  set: "Set",
  reset: "Reset",
  toassistant: "ToAssistant",
  openurl: "OpenUrl",
  copytoclipboard: "CopyToClipboard",
};

/** 已知内置函数的规范名（小写 → 规范写法）。与 `prompt-resident.txt` 的函数段一致。 */
const BUILTIN_FUNCTIONS: Record<string, string> = {
  append: "Append",
  avg: "Avg",
  clamp: "Clamp",
  count: "Count",
  default: "Default",
  each: "Each",
  filter: "Filter",
  format: "Format",
  formatdate: "FormatDate",
  includes: "Includes",
  join: "Join",
  percent: "Percent",
  plural: "Plural",
  range: "Range",
  remove: "Remove",
  round: "Round",
  sum: "Sum",
  today: "Today",
  toggle: "Toggle",
};

/**
 * `@//set/(` / `@//clamp/(` —— 模型把 action 或内置函数记成了路径语法。
 * 结尾的斜杠是误加的，名字与括号之间可能夹空白。
 * 只匹配紧跟 `(` 的调用形态；正则不识别字符串上下文，字符串里的同样文本
 * 也会命中，因此最终仍依赖调用方的重渲染校验。
 */
const MALFORMED_ACTION = /@\/\/\s*([A-Za-z]+)\s*\/\s*\(/g;

/**
 * `Text(..., "3xl", ...)` —— `Text` 的 size 只接受 xs/sm/md/lg，xl 及以上是 `Title` 才有的。
 * 模型想要大号数字却选错了组件。这里不换组件（会丢 weight / tabularNums 等参数），
 * 只把 size 钳到上界 `lg`。
 *
 * 中间的 `(?:...)` 把「完整字符串」和「完整括号组」当作整体吞掉，所以逗号不会在字符串
 * 或嵌套调用里被误当作参数分隔符（`Text("a, b", "3xl")` 也能正确命中第二个参数）。
 */
const TEXT_OVERSIZED_SIZE = /(\bText\s*\((?:[^"(),]|"[^"]*"|\([^()]*\))*?,\s*)"(xl|2xl|3xl|4xl)"/g;

/** Text 的 size 取值上界。 */
const TEXT_MAX_SIZE = "lg";

const IDENTIFIER_START = /[\p{L}_$]/u;
const IDENTIFIER_PART = /[\p{L}\p{N}_$]/u;
const SIMPLE_OBJECT_KEY = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
const BARE_KEY_BOUNDARY = new Set([":", ",", "{", "}", '"', "'", "`", "\\", "\r", "\n", "?"]);
const BARE_KEY_INVALID_START = new Set(["[", "]", "(", ")", "@", "$"]);
const hasNonAscii = (value: string) =>
  Array.from(value).some((char) => (char.codePointAt(0) ?? 0) > 0x7f);

/**
 * 修正中文对象键和中文成员访问的两种解析器歧义。
 *
 * <p>只扫描字符串外的 `{` / `,` 之后、`:` 之前的对象键，避免误改正文里的
 * `Text("说明, 年份: 2025")`；同时把字符串外的 `对象.中文字段` 改成
 * `对象["中文字段"]`。简单 ASCII 键保持原样，缩小纠偏范围。</p>
 */
function normalizeChineseObjectSyntax(text: string, markRewritten: () => void): string {
  let out = "";
  let quote: '"' | "'" | "`" | null = null;
  let escaped = false;
  let braceDepth = 0;

  const readIdentifier = (start: number) => {
    const keyStart = start;
    if (keyStart >= text.length || !IDENTIFIER_START.test(text[keyStart])) return null;

    let keyEnd = keyStart + 1;
    while (keyEnd < text.length && IDENTIFIER_PART.test(text[keyEnd])) keyEnd++;
    return { keyStart, keyEnd, key: text.slice(keyStart, keyEnd) };
  };

  const readKey = (start: number) => {
    let offset = start;
    while (offset < text.length && /\s/.test(text[offset])) offset++;
    const keyStart = offset;
    if (keyStart >= text.length || BARE_KEY_INVALID_START.has(text[keyStart])) return null;

    let keyEnd = keyStart;
    while (keyEnd < text.length) {
      const char = text[keyEnd];
      if (char === ":") {
        const rawKey = text.slice(keyStart, keyEnd);
        const key = rawKey.trimEnd();
        return key && !SIMPLE_OBJECT_KEY.test(key) ? { keyStart, keyEnd, key } : null;
      }
      if (BARE_KEY_BOUNDARY.has(char)) return null;
      keyEnd++;
    }
    return null;
  };

  for (let i = 0; i < text.length;) {
    const char = text[i];

    if (quote) {
      out += char;
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === quote) {
        quote = null;
      }
      i++;
      continue;
    }

    if (char === '"' || char === "'" || char === "`") {
      quote = char;
      out += char;
      i++;
      continue;
    }

    if (char === ".") {
      const member = readIdentifier(i + 1);
      if (member && hasNonAscii(member.key)) {
        out += `["${member.key}"]`;
        i = member.keyEnd;
        markRewritten();
        continue;
      }
    }

    if (char === "{") {
      braceDepth++;
      out += char;
      i++;
    } else if (char === "}") {
      braceDepth = Math.max(0, braceDepth - 1);
      out += char;
      i++;
    } else if (char === "," && braceDepth > 0) {
      out += char;
      i++;
    } else {
      out += char;
      i++;
      continue;
    }

    const key = readKey(i);
    if (!key) continue;
    out += text.slice(i, key.keyStart) + `"${key.key}"`;
    i = key.keyEnd;
    markRewritten();
  }

  return out;
}

/**
 * 只修正中文对象键和中文成员访问，不改 action、函数名或展示文本。
 *
 * <p>Parser 遇到未加引号的中文键不会报错，而是静默生成 `{"?": ...}`；因此
 * 即使原文仍能解析，也必须优先尝试这类确定的 Unicode 语法纠偏。</p>
 */
export function normalizeOpenUiUnicodeSyntax(text: string): string | null {
  let rewritten = false;
  const out = normalizeChineseObjectSyntax(text, () => {
    rewritten = true;
  });
  return rewritten ? out : null;
}

/**
 * 把程序体里写歪的地方改回规范写法。
 *
 * <p>返回 null 表示"没动过"，调用方据此跳过后续判断。只认 {@link ACTION_STEPS} /
 * {@link BUILTIN_FUNCTIONS} 里的名字，未知名字原样保留——宁可漏纠，也不要把没见过的写法
 * 改成别的意思。</p>
 *
 * <p>注意这里不做引号感知：一段展示文本里如果恰好含 `@//set/(`，也会被替换。调用方的
 * 「纠偏后可渲染才采用」策略会把这种误伤挡回去。</p>
 */
export function normalizeOpenUiProgram(text: string): string | null {
  let rewritten = false;

  const withActions = text.replace(MALFORMED_ACTION, (whole, rawName: string) => {
    const key = rawName.toLowerCase();
    const canonical = ACTION_STEPS[key] || BUILTIN_FUNCTIONS[key];
    if (!canonical) return whole;
    rewritten = true;
    return `@${canonical}(`;
  });

  const withText = withActions.replace(TEXT_OVERSIZED_SIZE, (_whole, prefix: string) => {
    rewritten = true;
    return `${prefix}"${TEXT_MAX_SIZE}"`;
  });

  const unicodeNormalized = normalizeOpenUiUnicodeSyntax(withText);
  const out = unicodeNormalized ?? withText;
  if (unicodeNormalized !== null) {
    rewritten = true;
  }

  return rewritten ? out : null;
}
