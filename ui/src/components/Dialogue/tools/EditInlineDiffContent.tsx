import { memo, useEffect, useMemo, useState } from "react";
import type { BundledLanguage, ThemedToken } from "shiki";
import { useCodePreviewWrap } from "@/hooks/useCodePreviewWrap";
import { useIsDarkTheme } from "@/hooks/useIsDarkTheme";
import { DEFAULT_CODE_PREVIEW_SETTINGS } from "@/lib/codePreviewSettings";
import { resolveCodeLanguage } from "@/lib/filePreview/language";
import { getLightweightDiffLineParts, getPlainTextPatchPreviewLines } from "@/lib/patchPreview";
import { tokenStyle, tokenizeCode } from "@/lib/shikiTokens";
import { shouldTokenizeCode } from "../markdown/codeHighlightPolicy";
import { LightweightDiffPreview } from "./LightweightDiffPreview";

/** 超过这个字符数就放弃语法高亮：内联 diff 不值得为它阻塞主线程。 */
const MAX_HIGHLIGHT_CHARS = 120_000;

type EditInlineDiffContentProps = {
  /** unified diff 文本（Cute 由 `buildEditDiffCode()` 产出） */
  patch: string;
  /** 目标文件路径，用于推断高亮语言 */
  path?: string;
  title?: string;
  /**
   * 工具仍在执行 / 参数仍在生成。
   *
   * 对齐 ZCode `message.tsx` 的 `enableSyntaxHighlighting={!renderStreaming}`：
   * 流式期间 `patch` 每帧都在变，而一次 tokenize 实测 ~116ms（30KB HTML）。
   * 不门禁的话每帧都是一次全量 TextMate 高亮，主线程被直接打满 ——
   * 表现就是「展开后整个前端卡住」。落定后 effect 会因 `streaming` 变化重跑一次。
   */
  streaming?: boolean;
};

/**
 * Edit 卡展开体的内联 diff（对齐 ZCode `renderers/EditInlineDiffContent.tsx`）。
 *
 * 首帧只渲染纯文本 hunk，再在 effect 里异步补 shiki token —— 直接同步高亮会把
 * 展开动作卡住。这里刻意不复用 `@pierre/diffs`：内联场景要的是「一眼看清改了哪几行」，
 * 不是可交互的完整 diff 查看器，后者在消息流里会带来 Shadow DOM 与横向滚动条的额外成本。
 */
export const EditInlineDiffContent = memo(function EditInlineDiffContent({
  patch,
  path,
  title,
  streaming = false,
}: EditInlineDiffContentProps) {
  const isDark = useIsDarkTheme();
  const [wrapLongLines] = useCodePreviewWrap();
  const [tokens, setTokens] = useState<ThemedToken[][] | null>(null);

  const lines = useMemo(() => getPlainTextPatchPreviewLines(patch), [patch]);
  // 高亮输入必须是「去掉协议前缀后的内容」，否则 shiki 会把 `+` / `-` 当成运算符着色。
  const highlightCode = useMemo(
    () => lines.map((line) => getLightweightDiffLineParts(line).content).join("\n"),
    [lines]
  );
  const language = useMemo(
    () => resolveCodeLanguage(path ?? title) as BundledLanguage,
    [path, title]
  );

  useEffect(() => {
    let alive = true;
    setTokens(null);

    if (
      !shouldTokenizeCode(highlightCode, streaming) ||
      highlightCode.length > MAX_HIGHLIGHT_CHARS
    ) {
      return () => {
        alive = false;
      };
    }

    tokenizeCode(highlightCode, language, isDark)
      .then((tokenized) => {
        if (alive) setTokens(tokenized.tokens);
      })
      .catch((error) => {
        // 语言不可用 / shiki 加载失败都只是丢掉高亮，纯文本预览仍然完整可读。
        // dev 下留一条告警，便于区分「预期的语言回退」与真正的实现 bug。
        if (import.meta.env.DEV) {
          console.warn("[EditInlineDiffContent] 语法高亮失败，已回退纯文本", error);
        }
        if (alive) setTokens(null);
      });

    return () => {
      alive = false;
    };
  }, [highlightCode, language, isDark, streaming]);

  return (
    <div className="kimi-inline-diff" data-testid="edit-inline-diff">
      <LightweightDiffPreview
        lines={lines}
        showLineNumbers={DEFAULT_CODE_PREVIEW_SETTINGS.showLineNumbers}
        wrapLongLines={wrapLongLines}
        fontSizePx={DEFAULT_CODE_PREVIEW_SETTINGS.fontSizePx}
        renderLineContent={(parts, index) => {
          const lineTokens = tokens?.[index];
          if (!lineTokens || lineTokens.length === 0) {
            return parts.content || " ";
          }
          return lineTokens.map((token, tokenIndex) => (
            <span key={tokenIndex} style={tokenStyle(token)}>
              {token.content}
            </span>
          ));
        }}
      />
    </div>
  );
});

EditInlineDiffContent.displayName = "EditInlineDiffContent";
