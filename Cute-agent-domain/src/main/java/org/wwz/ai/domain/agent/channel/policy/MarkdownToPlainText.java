package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Markdown 简化成 QQ 纯文本。
 * <p>QQ 的 C2C / 群聊只支持 {@code msg_type=0} 纯文本（markdown 仅频道可用），所以必须降级。
 * 原则是保留结构、去掉标记，但<b>代码块原样保留</b>——代码里的 {@code #} 和 {@code *} 是内容不是标记。</p>
 */
public final class MarkdownToPlainText {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]*)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<!\\w)_(?!_)(.+?)(?<!\\w)_(?!\\w)");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|[\\s:|-]*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^[-*_]\\s*[-*_]\\s*[-*_][-\\s*_]*$");
    private static final Pattern BLOCKQUOTE_PREFIX = Pattern.compile("^\\s*>\\s?");

    private MarkdownToPlainText() {
    }

    public static String convert(String markdown) {
        if (StringUtils.isBlank(markdown)) {
            return "";
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length());
        boolean inFence = false;
        // 表格按块处理：连续的行一起看，才能拿到表头当列名。
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                appendLine(out, line);
                i++;
                continue;
            }
            if (inFence) {
                appendLine(out, line);
                i++;
                continue;
            }

            if (isTableRow(trimmed) && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                i = convertTable(lines, i, out);
                continue;
            }

            appendLine(out, convertLine(line));
            i++;
        }
        return collapseBlankLines(out.toString()).trim();
    }

    private static boolean isTableRow(String trimmed) {
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    /**
     * 表格转成「列名: 值」逐行输出，比对齐的纯文本更适合手机窄屏阅读。
     *
     * @return 表格块之后的下一行下标
     */
    private static int convertTable(String[] lines, int start, StringBuilder out) {
        String[] headers = splitRow(lines[start]);
        int i = start + 2;
        while (i < lines.length && isTableRow(lines[i].trim())) {
            String[] cells = splitRow(lines[i]);
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < cells.length; c++) {
                if (StringUtils.isBlank(cells[c])) {
                    continue;
                }
                if (row.length() > 0) {
                    row.append(", ");
                }
                String header = c < headers.length && StringUtils.isNotBlank(headers[c])
                        ? headers[c]
                        : "列" + (c + 1);
                row.append(header).append(": ").append(cells[c]);
            }
            if (row.length() > 0) {
                appendLine(out, "- " + row);
            }
            i++;
        }
        return i;
    }

    private static String[] splitRow(String line) {
        String body = line.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] raw = body.split("\\|", -1);
        String[] cells = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            cells[i] = stripInline(raw[i].trim());
        }
        return cells;
    }

    private static String convertLine(String line) {
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            // 标题独占一行并留空行，手机端才看得出层级。
            return "\n" + stripInline(heading.group(1)) + "\n";
        }
        String trimmed = line.stripLeading();
        if (trimmed.startsWith(">")) {
            // 引用去掉 > 标记但保留缩进语义，不引入新前缀避免和列表混淆。
            return BLOCKQUOTE_PREFIX.matcher(line).replaceFirst("");
        }
        if (HORIZONTAL_RULE.matcher(trimmed).matches()) {
            // 分隔线在纯文本里没有意义。
            return "";
        }
        return stripInline(line);
    }

    private static String stripInline(String text) {
        String result = LINK.matcher(text).replaceAll(m -> {
            String label = m.group(1) == null ? "" : m.group(1).trim();
            String url = m.group(2) == null ? "" : m.group(2).trim();
            if (url.isEmpty()) {
                return label;
            }
            return label.isEmpty() ? url : label + " " + url;
        });
        result = BOLD.matcher(result).replaceAll("$1");
        result = ITALIC_STAR.matcher(result).replaceAll("$1");
        result = ITALIC_UNDERSCORE.matcher(result).replaceAll("$1");
        result = STRIKE.matcher(result).replaceAll("$1");
        result = INLINE_CODE.matcher(result).replaceAll("$1");
        return result;
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(line);
    }

    /** 连续空行压成一个，避免 QQ 里出现大片空白。 */
    private static String collapseBlankLines(String text) {
        return text.replaceAll("\n{3,}", "\n\n");
    }
}
