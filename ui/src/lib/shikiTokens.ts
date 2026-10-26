import type { CSSProperties } from "react";
import type { BundledLanguage, BundledTheme, ThemedToken } from "shiki";
import { loadShiki } from "@/lib/lazy/shiki";
import { hashContent } from "@/lib/hashContent";

/**
 * 代码高亮的唯一入口（对齐 ZCode `lib/shikiHighlighter.ts`）。
 *
 * 三件事缺一不可，都是聊天流里「同一段代码被反复高亮」的成本来源：
 * 1. **纯文本短路**：`text` / `log` / `output` 这类语言没有高亮收益，却会让每次
 *    渲染都进一遍 shiki 状态机。直接返回 raw tokens。
 * 2. **token 缓存**：同一份代码 + 语言 + 主题只 tokenize 一次。实测 30KB HTML
 *    单次 116ms，缓存后是 0。
 * 3. **同 key 订阅去重**：多个组件同时请求同一段代码时共用一个 in-flight promise，
 *    不再各跑一遍。
 */

const TEXT_LANGUAGE = "text" as BundledLanguage;

/** 无语法高亮收益的语言：直接走纯文本，不进 shiki。 */
const PLAIN_TEXT_LANGUAGES = new Set([
  "",
  "text",
  "txt",
  "plain",
  "plaintext",
  "log",
  "output",
]);

/** 缓存上限。超长会话里代码块会持续增多，不设上限就是内存泄漏。 */
const MAX_TOKEN_CACHE_ENTRIES = 400;

export type TokenizedCode = {
  tokens: ThemedToken[][];
};

/**
 * 这段代码是否值得交给 shiki。
 *
 * 与 `markdown/codeHighlightPolicy.ts` 的分工：那边管「流式期间跳过高亮」，
 * 这里管「语言本身没有高亮收益」。两个条件互相独立。
 */
export function shouldUseSyntaxHighlighting(language: string): boolean {
  return !PLAIN_TEXT_LANGUAGES.has(language.trim().toLowerCase());
}

/**
 * shiki token → 内联样式。
 *
 * shiki 的 `FontStyle` 是位掩码（italic=1 / bold=2 / underline=4），
 * 直接写进 `fontStyle` 会失效，必须逐位翻译成 CSS 属性。
 */
export function tokenStyle(token: ThemedToken): CSSProperties | undefined {
  if (!token.color && !token.fontStyle) {
    return undefined;
  }
  const style: CSSProperties = {};
  if (token.color) {
    style.color = token.color;
  }
  const fontStyle = token.fontStyle ?? 0;
  if (fontStyle & 1) {
    style.fontStyle = "italic";
  }
  if (fontStyle & 2) {
    style.fontWeight = 700;
  }
  if (fontStyle & 4) {
    style.textDecoration = "underline";
  }
  return style;
}

/** 纯文本 token：与 shiki 的 `ThemedToken` 同形，调用方不必区分两条路径。 */
function createRawTokens(code: string): ThemedToken[][] {
  return code.split("\n").map((line) =>
    line === "" ? [] : [{
      color: "inherit",
      content: line
    } as ThemedToken]
  );
}

/**
 * 缓存 key：用内容哈希而不是完整代码，避免把几十 KB 的源码复制进 Map 的 key。
 *
 * 拼上长度是为了让碰撞概率再降一档 —— `hashContent` 是 djb2，不是密码学哈希。
 */
function createTokenCacheKey(
  code: string,
  language: BundledLanguage,
  dark: boolean
): string {
  return `${dark ? "dark" : "light"}:${language}:${code.length}:${hashContent(code)}`;
}

const tokenCache = new Map<string, TokenizedCode>();
/** in-flight 请求：同一 key 的并发调用共用一次 tokenize。 */
const pendingTokens = new Map<string, Promise<TokenizedCode>>();

function readTokenCache(key: string): TokenizedCode | undefined {
  const cached = tokenCache.get(key);
  if (cached) {
    // 命中时挪到队尾，淘汰时优先丢掉最久没被读过的条目。
    tokenCache.delete(key);
    tokenCache.set(key, cached);
  }
  return cached;
}

function writeTokenCache(key: string, value: TokenizedCode): void {
  tokenCache.delete(key);
  tokenCache.set(key, value);
  while (tokenCache.size > MAX_TOKEN_CACHE_ENTRIES) {
    const oldest = tokenCache.keys().next();
    if (oldest.done) break;
    tokenCache.delete(oldest.value);
  }
}

/**
 * 逐行 tokenize（异步）。未知语言回退到 `text`，`text` 自身失败则抛错由调用方兜底。
 *
 * 与 `KimiCodeFence` / 内联 diff 共用，避免两处各写一份 shiki 调用与回退规则。
 */
export function tokenizeCode(
  code: string,
  language: BundledLanguage,
  dark: boolean
): Promise<TokenizedCode> {
  if (!shouldUseSyntaxHighlighting(language)) {
    return Promise.resolve({ tokens: createRawTokens(code) });
  }

  const cacheKey = createTokenCacheKey(code, language, dark);
  const cached = readTokenCache(cacheKey);
  if (cached) {
    return Promise.resolve(cached);
  }

  const pending = pendingTokens.get(cacheKey);
  if (pending) {
    return pending;
  }

  const theme: BundledTheme = dark ? "github-dark" : "github-light";
  const run = (async (): Promise<TokenizedCode> => {
    const { codeToTokens } = await loadShiki();
    const tokenize = async (lang: BundledLanguage) => {
      const result = await codeToTokens(code, {
        lang,
        theme
      });
      return { tokens: result.tokens } satisfies TokenizedCode;
    };

    try {
      return await tokenize(language);
    } catch {
      if (language === TEXT_LANGUAGE) throw new Error("Shiki language unavailable");
      return await tokenize(TEXT_LANGUAGE);
    }
  })()
    .then((tokenized) => {
      writeTokenCache(cacheKey, tokenized);
      return tokenized;
    })
    .finally(() => {
      pendingTokens.delete(cacheKey);
    });

  pendingTokens.set(cacheKey, run);
  return run;
}

/** 仅测试使用：清空缓存，避免用例之间互相污染。 */
export function resetShikiTokenCache(): void {
  tokenCache.clear();
  pendingTokens.clear();
}
