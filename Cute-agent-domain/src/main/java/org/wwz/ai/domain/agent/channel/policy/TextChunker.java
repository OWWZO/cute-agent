package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 长文本切块。
 * <p>QQ 单条上限 4000 字符。必须切块而不是截断——截断会直接丢内容。切点优先落在代码块边界之外，
 * 实在没有就退到换行、再退到硬切。</p>
 */
public final class TextChunker {

    /** QQ {@code MAX_MESSAGE_LENGTH}。 */
    public static final int MAX_LENGTH = 4000;

    /** 给 {@code (n/N)} 序号预留的余量。 */
    private static final int SEQ_RESERVE = 12;

    private static final String FENCE = "```";

    private TextChunker() {
    }

    /**
     * 按上限切块，块数大于 1 时每块带 {@code (n/N)} 前缀。
     * <p>序号只在确定要切时才加，短文本保持原样。</p>
     */
    public static List<String> split(String text) {
        return split(text, MAX_LENGTH);
    }

    public static List<String> split(String text, int maxLength) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        if (maxLength <= SEQ_RESERVE * 2) {
            throw new IllegalArgumentException("maxLength 过小：" + maxLength);
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= maxLength) {
            return List.of(normalized);
        }

        int budget = maxLength - SEQ_RESERVE;
        List<String> raw = new ArrayList<>();
        int cursor = 0;
        boolean inFence = false;
        while (cursor < normalized.length()) {
            int remaining = normalized.length() - cursor;
            if (remaining <= budget) {
                String tail = normalized.substring(cursor);
                raw.add(inFence ? FENCE + "\n" + tail : tail);
                break;
            }
            int limit = cursor + budget;
            int cut = findCut(normalized, cursor, limit, inFence);
            String chunk = normalized.substring(cursor, cut);
            // 统计本块内 fence 数，决定下一块是否要从「重开代码块」开始。
            boolean chunkInFence = inFence;
            inFence = inFence ^ (countFences(chunk) % 2 == 1);
            StringBuilder piece = new StringBuilder();
            if (chunkInFence) {
                piece.append(FENCE).append('\n');
            }
            piece.append(chunk.stripTrailing());
            if (inFence) {
                piece.append('\n').append(FENCE);
            }
            raw.add(piece.toString());
            cursor = cut;
            while (cursor < normalized.length() && normalized.charAt(cursor) == '\n') {
                cursor++;
            }
        }

        int total = raw.size();
        List<String> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            result.add("(" + (i + 1) + "/" + total + ")\n" + raw.get(i));
        }
        return result;
    }

    /**
     * 选切点：优先空行，其次换行，再次句末标点，最后硬切。
     * <p>代码块内也允许按换行切——重开 fence 已经保证了语法可见性。</p>
     */
    private static int findCut(String text, int from, int limit, boolean inFence) {
        if (!inFence) {
            int paragraph = text.lastIndexOf("\n\n", limit);
            if (paragraph > from) {
                return paragraph;
            }
        }
        int newline = text.lastIndexOf('\n', limit);
        if (newline > from) {
            return newline;
        }
        int sentence = lastSentenceBoundary(text, from, limit);
        if (sentence > from) {
            return sentence + 1;
        }
        return limit;
    }

    private static int lastSentenceBoundary(String text, int from, int limit) {
        for (int i = limit - 1; i > from; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return -1;
    }

    private static int countFences(String chunk) {
        int count = 0;
        int index = chunk.indexOf(FENCE);
        while (index >= 0) {
            count++;
            index = chunk.indexOf(FENCE, index + FENCE.length());
        }
        return count;
    }
}
