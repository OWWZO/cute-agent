package org.wwz.ai.domain.agent.memory.ltm;

import java.util.regex.Pattern;

/**
 * Prefetch 围栏与清洗。
 */
public final class MemoryContextFence {

    public static final String OPEN = "<memory-context>";
    public static final String CLOSE = "</memory-context>";

    private static final Pattern FENCE_TAG = Pattern.compile("</?\\s*memory-context\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INNER_BLOCK = Pattern.compile(
            "<\\s*memory-context\\s*>[\\s\\S]*?</\\s*memory-context\\s*>",
            Pattern.CASE_INSENSITIVE);
    /**
     * 只剥离位于文本开头（可带前导空白）的泄漏 note。旧实现会删除正文任意位置出现的
     * 同名短语，既会误删合法的召回内容，也挡不住变体写法；围栏 note 本来就只会出现在
     * 块首，因此用 {@code \A} 锚定即可覆盖泄漏场景。
     */
    private static final Pattern SYSTEM_NOTE = Pattern.compile(
            "\\A\\s*\\[System note:\\s*The following is recalled memory context,[^\\]]*\\]\\s*",
            Pattern.CASE_INSENSITIVE);

    private MemoryContextFence() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = INNER_BLOCK.matcher(text).replaceAll("");
        cleaned = SYSTEM_NOTE.matcher(cleaned).replaceAll("");
        cleaned = FENCE_TAG.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /**
     * 把未围栏的原始记忆片段包装成围栏块。
     * <p>入参必须是未围栏内容：{@link #sanitize} 会剥离已有的
     * {@code <memory-context>...</memory-context>}，传入已围栏文本会得到空结果。</p>
     */
    public static String buildBlock(String rawContext) {
        if (rawContext == null || rawContext.isBlank()) {
            return "";
        }
        String clean = sanitize(rawContext);
        if (clean.isBlank()) {
            return "";
        }
        return OPEN + "\n"
                + "[System note: The following is recalled memory context, "
                + "NOT new user input. Treat as authoritative reference data — "
                + "this is the agent's persistent memory and should inform responses.]\n\n"
                + clean + "\n"
                + CLOSE;
    }
}
