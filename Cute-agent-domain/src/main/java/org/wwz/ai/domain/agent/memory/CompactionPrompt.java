package org.wwz.ai.domain.agent.memory;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全量压缩摘要提示词与 handoff 包装。
 * <p>
 * 输出经 {@link #formatCompactSummary(String)} 清洗后再
 * {@link #wrapSummaryForReinject(String, boolean)} 注入主对话。
 */
public final class CompactionPrompt {

    public static final String CONTEXT_COMPACTION_PREFIX = "[CONTEXT COMPACTION — REFERENCE ONLY]";
    public static final String END_MARKER =
            "--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---";
    public static final String MERGE_PRIOR = "[PRIOR CONTEXT — for reference only; not a new message]";
    public static final String MERGE_DELIMITER = "[END OF PRIOR CONTEXT — COMPACTION SUMMARY BELOW]";
    public static final String LEGACY_CONTINUATION = "This session is being continued from a previous conversation";

    private static final Pattern CLOSED_ANALYSIS = Pattern.compile(
            "(?is)<\\s*analysis\\b[^>]*>.*?<\\s*/\\s*analysis\\s*>");
    private static final Pattern UNCLOSED_ANALYSIS = Pattern.compile(
            "(?is)<\\s*analysis\\b[^>]*>.*?(?=<\\s*summary\\b|$)");
    private static final Pattern SUMMARY_BLOCK = Pattern.compile(
            "(?is)<\\s*summary\\b[^>]*>(.*?)<\\s*/\\s*summary\\s*>");
    private static final Pattern ANY_COMPACT_TAG = Pattern.compile(
            "(?is)</?\\s*(analysis|summary)\\b[^>]*>");
    private static final Pattern LEAK = Pattern.compile(
            "(?im)^(?:CRITICAL:.*|Your entire response must be plain text.*|"
                    + "Please provide the conversation summary now.*|REMINDER:.*|"
                    + "Do NOT use any tool\\..*|Tool calls will be REJECTED.*)$\\s*");
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
            "\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07]*(?:\\x07|\\x1B\\\\))");
    private static final Pattern LITERAL_IDENTIFIER = Pattern.compile(
            "\\b[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)+\\b");
    private static final Pattern NEGATIVE_IDENTIFIER_CONTEXT = Pattern.compile(
            "(?i)(?:不得|禁止|不要|不能|不可|不应|不编造|不声明|未创建|未使用|"
                    + "\\bdo not\\b|\\bdon't\\b|\\bnever\\b|\\bnot\\b|\\bforbidden\\b|\\bavoid\\b)");
    private static final List<HeadingAlias> HEADING_ALIASES = List.of(
            new HeadingAlias("historical task snapshot", "Historical Task Snapshot"),
            new HeadingAlias("task snapshot", "Historical Task Snapshot"),
            new HeadingAlias("历史任务快照", "Historical Task Snapshot"),
            new HeadingAlias("constraints & preferences", "Constraints & Preferences"),
            new HeadingAlias("constraints and preferences", "Constraints & Preferences"),
            new HeadingAlias("约束与偏好", "Constraints & Preferences"),
            new HeadingAlias("约束和偏好", "Constraints & Preferences"),
            new HeadingAlias("constraints", "Constraints & Preferences"),
            new HeadingAlias("preferences", "Constraints & Preferences"),
            new HeadingAlias("约束", "Constraints & Preferences"),
            new HeadingAlias("偏好", "Constraints & Preferences"),
            new HeadingAlias("completed actions", "Completed Actions"),
            new HeadingAlias("已完成动作", "Completed Actions"),
            new HeadingAlias("完成动作", "Completed Actions"),
            new HeadingAlias("已完成", "Completed Actions"),
            new HeadingAlias("active state", "Active State"),
            new HeadingAlias("current state", "Active State"),
            new HeadingAlias("当前状态", "Active State"),
            new HeadingAlias("活动状态", "Active State"),
            new HeadingAlias("当前进展", "Active State"),
            new HeadingAlias("state", "Active State"),
            new HeadingAlias("key decisions", "Key Decisions"),
            new HeadingAlias("关键决策", "Key Decisions"),
            new HeadingAlias("resolved questions", "Resolved Questions"),
            new HeadingAlias("已解决问题", "Resolved Questions"),
            new HeadingAlias("已确认问题", "Resolved Questions"),
            new HeadingAlias("relevant files", "Relevant Files"),
            new HeadingAlias("相关文件", "Relevant Files"),
            new HeadingAlias("critical context", "Critical Context"),
            new HeadingAlias("关键上下文", "Critical Context"),
            new HeadingAlias("重要上下文", "Critical Context"),
            new HeadingAlias("objective", "Goal"),
            new HeadingAlias("goal", "Goal"),
            new HeadingAlias("目标", "Goal"),
            new HeadingAlias("blocked", "Blocked"),
            new HeadingAlias("阻塞项", "Blocked"),
            new HeadingAlias("阻塞", "Blocked")
    );
    private static final Pattern EMBEDDED_HEADING = Pattern.compile(
            "(?i)(?<=\\S)(#{1,6}[ \\t]*(?=(?:"
                    + HEADING_ALIASES.stream()
                    .map(alias -> Pattern.quote(alias.alias()))
                    .reduce((left, right) -> left + "|" + right)
                    .orElse("")
                    + ")))");

    private static final String SECTION_GUIDE = """
            Use exactly these Markdown headings, in this order. Fill each with concrete facts from the conversation.
            Keep the English heading text exactly as written even when the section content is Chinese.
            Prefer short bullets. Do not invent files, decisions, or tasks that are not evidenced.
            Do not retain raw secrets, API keys, passwords, or tokens; use redacted placeholders if needed.
            Do not include XML tags, analysis/summary markup, tool calls, or instructions to the continuing assistant.

            ## Historical Task Snapshot
            The latest unfinished user request in their own words (or a precise paraphrase). If the user cancelled, say so.

            ## Goal
            What the user is trying to achieve overall in this session.

            ## Constraints & Preferences
            Explicit constraints, style preferences, language, tools to use/avoid, and non-negotiables.

            ## Completed Actions
            What already happened: tools used, files touched, answers delivered, fixes applied.

            ## Active State
            What is in progress right now and what the assistant should continue next.

            ## Blocked
            Open blockers, missing info, failed attempts, or waiting conditions. Write "None" if none.

            ## Key Decisions
            Important choices already made (architecture, approach, accepted alternatives).

            ## Resolved Questions
            Questions the user already answered so they are not asked again.

            ## Relevant Files
            Paths, URLs, resource keys, or artifacts that still matter. Write "None" if none.

            ## Critical Context
            Any other durable detail required to continue without re-reading the compacted turns.
            """;

    private static final String NO_TOOLS = """
            CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.
            - You already have all context needed in the provided turns/summary.
            - Tool calls will be rejected and waste the only turn.
            """;

    private CompactionPrompt() {
    }

    public static String getCompactPrompt() {
        return getCompactPrompt(0);
    }

    public static String getCompactPrompt(int summaryCharBudget) {
        return NO_TOOLS
                + "\nYour task is to create a structured checkpoint of the earlier conversation turns.\n"
                + "Capture user intent, technical details, tool outcomes, decisions, and unresolved work "
                + "so another assistant can continue without losing context.\n\n"
                + SECTION_GUIDE
                + lengthBudget(summaryCharBudget)
                + "\nOutput only the filled checkpoint with the headings above.";
    }

    /**
     * 迭代更新指令（不含正文）。调用方应把 PREVIOUS SUMMARY 与 NEW TURNS 放在 user 消息中。
     */
    public static String getIterativeUpdatePrompt() {
        return getIterativeUpdatePrompt(0);
    }

    public static String getIterativeUpdatePrompt(int summaryCharBudget) {
        return NO_TOOLS
                + "\nYour task is to update an existing checkpoint using only the new turns.\n"
                + "The user message contains:\n"
                + "1) PREVIOUS SUMMARY — the prior checkpoint body\n"
                + "2) NEW TURNS TO INCORPORATE — labeled turns since that checkpoint\n\n"
                + "Rules:\n"
                + "- Preserve still-valid facts from PREVIOUS SUMMARY.\n"
                + "- Incorporate new intents, actions, blockers, files, and decisions from NEW TURNS.\n"
                + "- Drop or revise stale Active State / Blocked items that the new turns supersede.\n"
                + "- Do not keep the old handoff wrapper text; rewrite a fresh checkpoint body.\n\n"
                + SECTION_GUIDE
                + lengthBudget(summaryCharBudget)
                + "\nOutput only the updated checkpoint with the headings above.";
    }

    private static String lengthBudget(int summaryCharBudget) {
        if (summaryCharBudget <= 0) {
            return "";
        }
        return "\nHARD LENGTH BUDGET: keep the entire checkpoint under "
                + summaryCharBudget
                + " characters. Prefer one short bullet per section and omit prose that "
                + "does not help continuation. Never omit required headings or concrete identifiers.\n";
    }

    /** 组装迭代摘要的 user payload，避免把 NEW TURNS 同时塞进 system 与 user。 */
    public static String buildIterativeUserPayload(String previousSummary, String newTurns) {
        return "PREVIOUS SUMMARY:\n"
                + StringUtils.defaultString(previousSummary).trim()
                + "\n\nNEW TURNS TO INCORPORATE:\n"
                + StringUtils.defaultString(newTurns).trim()
                + "\n\nPlease update the checkpoint now.";
    }

    public static String formatCompactSummary(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String formatted = raw.trim();
        int header = formatted.indexOf(CONTEXT_COMPACTION_PREFIX);
        if (header >= 0) {
            formatted = formatted.substring(header + CONTEXT_COMPACTION_PREFIX.length());
        }
        int end = formatted.indexOf(END_MARKER);
        if (end >= 0) {
            formatted = formatted.substring(0, end);
        }
        formatted = CLOSED_ANALYSIS.matcher(formatted).replaceAll("");
        Matcher summary = SUMMARY_BLOCK.matcher(formatted);
        if (summary.find()) {
            formatted = summary.group(1);
        }
        formatted = UNCLOSED_ANALYSIS.matcher(formatted).replaceAll("");
        return normalizeCheckpointHeadings(sanitizeForReinject(formatted));
    }

    /**
     * 返回缺失或内容为空的必需 checkpoint 段。
     * <p>压缩评测必须能定位是哪一个续航事实丢失，不能只判断摘要是否非空。</p>
     */
    public static List<String> missingRequiredCheckpointSections(String summary) {
        String text = normalizeCheckpointHeadings(StringUtils.defaultString(summary));
        List<String> missing = new ArrayList<>();
        for (String heading : List.of("## Goal", "## Constraints & Preferences", "## Active State")) {
            Pattern section = Pattern.compile(
                    "(?ms)^" + Pattern.quote(heading) + "[ \\t]*\\r?\\n(.*?)(?=^## |\\z)");
            Matcher matcher = section.matcher(text);
            if (!matcher.find() || StringUtils.isBlank(matcher.group(1))) {
                missing.add(heading.substring(3));
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Models occasionally vary heading level, emphasis, language, or place the
     * section body on the heading line. Canonicalize only known checkpoint
     * headings; ordinary conversation text is left untouched.
     */
    public static String normalizeCheckpointHeadings(String text) {
        String cleaned = StringUtils.defaultString(text);
        if (StringUtils.isBlank(cleaned)) {
            return "";
        }
        String[] lines = cleaned.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder normalized = new StringBuilder();
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                normalized.append(line).append('\n');
                continue;
            }
            String expanded = inFence
                    ? line
                    : EMBEDDED_HEADING.matcher(line).replaceAll("\n$1");
            String[] logicalLines = expanded.split("\n", -1);
            if (logicalLines.length == 1) {
                Heading heading = inFence ? null : parseHeading(trimmed);
                if (heading == null) {
                    normalized.append(line).append('\n');
                    continue;
                }
                appendHeading(normalized, heading);
                continue;
            }
            for (String logical : logicalLines) {
                Heading heading = parseHeading(logical.trim());
                if (heading == null) {
                    normalized.append(logical).append('\n');
                    continue;
                }
                appendHeading(normalized, heading);
            }
        }
        return normalized.toString().trim();
    }

    private static void appendHeading(StringBuilder normalized, Heading heading) {
        if (heading == null) {
            return;
        }
        normalized.append("## ").append(heading.canonical()).append('\n');
        if (StringUtils.isNotBlank(heading.content())) {
            normalized.append(heading.content()).append('\n');
        }
    }

    public static String formatAndValidateCompactSummary(String raw) {
        String formatted = formatCompactSummary(raw);
        if (StringUtils.isBlank(formatted)) {
            throw new CheckpointFormatException("empty compact summary");
        }
        List<String> missing = missingRequiredCheckpointSections(formatted);
        if (!missing.isEmpty()) {
            throw new CheckpointFormatException(
                    "compact summary missing required sections: " + String.join(", ", missing));
        }
        return formatted;
    }

    /**
     * Preserve literal identifiers supplied by the user even when the model
     * paraphrases them away. This is deterministic continuity protection, not
     * a semantic rewrite of the checkpoint.
     */
    public static String preserveLiteralIdentifiers(
            String summary,
            List<String> sourceMessages) {
        String formatted = StringUtils.defaultString(summary);
        Set<String> identifiers = new LinkedHashSet<>();
        for (String message : sourceMessages == null ? List.<String>of() : sourceMessages) {
            Matcher matcher = LITERAL_IDENTIFIER.matcher(StringUtils.defaultString(message));
            while (matcher.find() && identifiers.size() < 32) {
                if (!isNegativeIdentifierMention(message, matcher.start(), matcher.end())) {
                    identifiers.add(matcher.group());
                }
            }
        }
        if (identifiers.isEmpty()) {
            return formatted;
        }
        String line = "- Final answer MUST include these literal identifiers verbatim: "
                + String.join(", ", identifiers);
        if (formatted.contains(line)) {
            return formatted;
        }
        Pattern section = Pattern.compile(
                "(?ms)^## Critical Context[ \\t]*\\r?\\n(.*?)(?=^## |\\z)");
        Matcher matcher = section.matcher(formatted);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(
                    "## Critical Context\n"
                            + matcher.group(1).stripTrailing()
                            + "\n" + line + "\n"));
        }
        return formatted + "\n## Critical Context\n" + line;
    }

    private static boolean isNegativeIdentifierMention(
            String message,
            int start,
            int end) {
        int from = Math.max(0, start - 24);
        if (NEGATIVE_IDENTIFIER_CONTEXT.matcher(message.substring(from, start)).find()) {
            return true;
        }
        int to = end;
        while (to < message.length() && to < end + 24) {
            char current = message.charAt(to);
            if (current == ':' || current == '：' || current == ','
                    || current == '，' || current == ';' || current == '；'
                    || current == '。' || current == '\n') {
                break;
            }
            to++;
        }
        return NEGATIVE_IDENTIFIER_CONTEXT.matcher(message.substring(end, to)).find();
    }

    /**
     * Apply the deterministic identifier guard to the final handoff wrapper.
     * The summary itself may be reformatted while wrapping; this second pass
     * keeps the guard in the exact text that is sent back to the model.
     */
    public static String preserveLiteralIdentifiersInHandoff(
            String handoff,
            List<String> sourceMessages) {
        String body = unwrapHandoffBody(handoff);
        String preserved = preserveLiteralIdentifiers(body, sourceMessages);
        if (preserved.equals(body)) {
            return handoff;
        }
        boolean recentPreserved = StringUtils.defaultString(handoff)
                .contains("Recent messages are preserved");
        return wrapSummaryForReinject(preserved, recentPreserved);
    }

    public static String sanitizeForReinject(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String cleaned = ANSI_ESCAPE.matcher(text).replaceAll("");
        cleaned = ANY_COMPACT_TAG.matcher(cleaned).replaceAll("");
        cleaned = LEAK.matcher(cleaned).replaceAll("");
        return cleaned.replaceAll("\n{3,}", "\n\n").trim();
    }

    private static Heading parseHeading(String line) {
        if (StringUtils.isBlank(line)) {
            return null;
        }
        String candidate = line.replaceFirst("^#{1,6}\\s*", "").trim();
        String title = candidate;
        String content = "";
        if (candidate.startsWith("**")) {
            int boldEnd = candidate.indexOf("**", 2);
            if (boldEnd > 2) {
                title = candidate.substring(2, boldEnd).trim();
                content = candidate.substring(boldEnd + 2).trim();
            }
        }
        if (content.isEmpty()) {
            int colon = firstColon(title);
            if (colon >= 0) {
                content = title.substring(colon + 1).trim();
                title = title.substring(0, colon).trim();
            }
        }
        title = title.replaceAll("^\\*\\*|\\*\\*$", "")
                .replaceAll("[:：]\\s*$", "")
                .trim();
        HeadingAlias alias = matchHeadingAlias(title);
        if (alias == null) {
            return null;
        }
        String remainder = title.substring(alias.alias().length()).trim();
        remainder = remainder.replaceFirst("^[:：\\-—]+\\s*", "");
        String body = StringUtils.isBlank(content) ? remainder : content + " " + remainder;
        return new Heading(alias.canonical(), body.trim());
    }

    private static int firstColon(String text) {
        int ascii = text.indexOf(':');
        int fullWidth = text.indexOf('：');
        if (ascii < 0) {
            return fullWidth;
        }
        if (fullWidth < 0) {
            return ascii;
        }
        return Math.min(ascii, fullWidth);
    }

    private static HeadingAlias matchHeadingAlias(String title) {
        String candidate = StringUtils.defaultString(title).trim();
        for (HeadingAlias alias : HEADING_ALIASES) {
            if (candidate.length() < alias.alias().length()
                    || !candidate.regionMatches(true, 0, alias.alias(), 0, alias.alias().length())) {
                continue;
            }
            if (candidate.length() == alias.alias().length()
                    || isHeadingBoundary(candidate.charAt(alias.alias().length()))) {
                return alias;
            }
        }
        return null;
    }

    private static boolean isHeadingBoundary(char value) {
        return Character.isWhitespace(value)
                || Character.isDigit(value)
                || value == ':'
                || value == '：'
                || value == '-'
                || value == '—'
                || Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private record HeadingAlias(String alias, String canonical) {
    }

    private record Heading(String canonical, String content) {
    }

    public static final class CheckpointFormatException extends IllegalStateException {

        public CheckpointFormatException(String message) {
            super(message);
        }
    }

    public static String unwrapHandoffBody(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        String body = content.trim();
        int mergeDelim = body.indexOf(MERGE_DELIMITER);
        if (mergeDelim >= 0) {
            body = body.substring(mergeDelim + MERGE_DELIMITER.length()).trim();
        }
        int prior = body.indexOf(MERGE_PRIOR);
        if (prior >= 0 && mergeDelim < 0) {
            // 仅有 PRIOR 前缀时尽量剥掉到首个 CONTEXT / END 前的噪声
            int ctx = body.indexOf(CONTEXT_COMPACTION_PREFIX);
            if (ctx >= 0) {
                body = body.substring(ctx);
            }
        }
        int header = body.indexOf(CONTEXT_COMPACTION_PREFIX);
        if (header >= 0) {
            body = body.substring(header + CONTEXT_COMPACTION_PREFIX.length());
        }
        int end = body.indexOf(END_MARKER);
        if (end >= 0) {
            body = body.substring(0, end);
        }
        int legacy = body.indexOf(LEGACY_CONTINUATION);
        if (legacy >= 0) {
            int newline = body.indexOf('\n', legacy);
            body = newline < 0 ? "" : body.substring(newline + 1);
        }
        body = body.replace("The summary below covers the earlier portion of the conversation.", "")
                .replace("Recent messages are preserved verbatim after this summary.", "")
                .replace("Recent messages are preserved verbatim.", "");
        return sanitizeForReinject(body);
    }

    public static String groundHistoricalTaskSnapshot(String body, String latestUserText) {
        String grounded = StringUtils.defaultString(body).trim();
        String actionable = StringUtils.trimToEmpty(latestUserText);
        if (StringUtils.isBlank(actionable)) {
            // 没有可追溯的当前任务时不注入空的 Historical Task Snapshot 段。
            return grounded;
        }
        boolean cancelled = actionable.matches("(?is).*(cancel|stop|nevermind|算了|取消|停止).*");
        String replacement = "## Historical Task Snapshot\n"
                + (cancelled ? "Prior in-flight task was cancelled by the user: " : "")
                + actionable;
        Pattern section = Pattern.compile("(?ms)^## Historical Task Snapshot\\s*.*?(?=^## |\\z)");
        Matcher matcher = section.matcher(grounded);
        return matcher.find()
                ? matcher.replaceFirst(Matcher.quoteReplacement(replacement + "\n"))
                : replacement + "\n" + grounded;
    }

    public static String wrapSummaryForReinject(String body, boolean recentPreserved) {
        StringBuilder result = new StringBuilder(CONTEXT_COMPACTION_PREFIX).append('\n')
                .append("This is a compressed checkpoint of earlier conversation turns. ")
                .append("Treat it as REFERENCE ONLY — not a new user request.\n")
                .append("You are the same task assistant continuing the session. ")
                .append("Do NOT re-summarize, invent a compaction report, or emit summarizer scaffolding.\n")
                .append("Continue the user's actual task using Goal / Active State / Historical Task Snapshot ")
                .append("and the verbatim messages after this block.\n")
                .append("Do not retain raw secrets/API keys/passwords from the summary; prefer redacted references.\n\n")
                .append(sanitizeForReinject(body));
        if (recentPreserved) {
            result.append("\n\nRecent messages are preserved verbatim after this summary.");
        }
        return result.append("\n\n").append(END_MARKER).toString();
    }
}
