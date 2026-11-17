package org.wwz.ai.domain.agent.runtime.tool;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.MemoryTool;
import org.wwz.ai.domain.agent.runtime.tool.common.SessionSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.WebFetchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.WebSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.canvas.CanvasToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.McpToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AskUserQuestionTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.RequestDesktopControlTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.shell.BashTool;
import org.wwz.ai.domain.agent.runtime.tool.common.skill.SkillViewTool;
import org.wwz.ai.domain.agent.runtime.tool.common.skill.SkillsSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceReadTool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 工具结果投影：把结构化 llmData 压成主智能体可见的紧凑文本。
 * <p>
 * 对齐 zCode 的 {@code ToolEntry.formatModelContent}：模型只拿它需要的，UI / 账本仍拿完整 JSON。
 * 改造后三通道收敛为两列：
 * <ul>
 *   <li>{@code llm_oberserve} ← 本类产出（模型实际看到的文本，与 working_memory 一致）</li>
 *   <li>{@code tool_result} ← {@code llmData} 的完整 JSON（历史回放 / 前端卡片）</li>
 * </ul>
 * 未注册的工具、未接入失败投影的 ok=false、缺键或异常返回 {@code null}，由调用方回落 JSON 序列化；
 * Bash / Code Execution / CLI / 浏览器这些代码型工具的失败结果继续走紧凑 failure projector。
 */
@Slf4j
public final class ToolObservationFormatter {

    private static final Map<String, Function<Map<?, ?>, String>> PROJECTORS = Map.ofEntries(
            Map.entry("workspace_read", ToolObservationFormatter::projectRead),
            Map.entry("workspace_grep", ToolObservationFormatter::projectGrep),
            Map.entry("workspace_glob", ToolObservationFormatter::projectGlob),
            Map.entry("workspace_list", ToolObservationFormatter::projectList),
            Map.entry("workspace_edit", ToolObservationFormatter::projectEdit),
            Map.entry("workspace_write", ToolObservationFormatter::projectWrite),
            Map.entry(TaskToolNames.TASK_OUTPUT, ToolObservationFormatter::projectTaskOutput),
            Map.entry(SessionSearchTool.TOOL_NAME, ToolObservationFormatter::projectSessionSearch),
            Map.entry("host_cli", ToolObservationFormatter::projectCli),
            Map.entry("browser", ToolObservationFormatter::projectBrowser),
            Map.entry("agent_browser", ToolObservationFormatter::projectBrowser),
            // 检索类：命中列表是候选集，snippet 截断后仍是模型判断相关性的依据。
            Map.entry(WebSearchTool.TOOL_NAME, ToolObservationFormatter::projectWebSearch),
            Map.entry(WebFetchTool.TOOL_NAME, ToolObservationFormatter::projectWebFetch),
            Map.entry("deep_search", ToolObservationFormatter::projectDeepSearch),
            Map.entry(SkillViewTool.NAME, ToolObservationFormatter::projectSkillView),
            Map.entry(SkillsSearchTool.NAME, ToolObservationFormatter::projectSkillsSearch),
            // 子 Agent：agentId 必须保留（SendMessage 靠它续跑），其余元数据丢弃。
            Map.entry(AgentDispatchTool.NAME, ToolObservationFormatter::projectAgent),
            Map.entry(TaskToolNames.SEND_MESSAGE, ToolObservationFormatter::projectSendMessage),
            Map.entry(TaskToolNames.TASK_STOP, ToolObservationFormatter::projectTaskStop),
            Map.entry(TaskToolNames.TASK_CREATE, ToolObservationFormatter::projectTaskRecord),
            Map.entry(TaskToolNames.TASK_UPDATE, ToolObservationFormatter::projectTaskRecord),
            Map.entry(TaskToolNames.TASK_GET, ToolObservationFormatter::projectTaskRecord),
            Map.entry(TaskToolNames.TASK_LIST, ToolObservationFormatter::projectTaskList),
            Map.entry(TaskToolNames.TODO_WRITE, ToolObservationFormatter::projectTaskList),
            Map.entry(TaskToolNames.ENTER_PLAN_MODE, ToolObservationFormatter::projectEnterPlanMode),
            Map.entry(TaskToolNames.EXIT_PLAN_MODE, ToolObservationFormatter::projectExitPlanMode),
            Map.entry(AskUserQuestionTool.NAME, ToolObservationFormatter::projectAskUserQuestion),
            Map.entry(RequestDesktopControlTool.NAME, ToolObservationFormatter::projectDesktopControl),
            Map.entry(CanvasToolNames.CANVAS_PUBLISH, ToolObservationFormatter::projectCanvasPublish),
            Map.entry(McpToolNames.TOOL_SEARCH, ToolObservationFormatter::projectToolSearch),
            Map.entry(McpToolNames.TOOL_DESCRIBE, ToolObservationFormatter::projectToolDescribe),
            Map.entry(MemoryTool.TOOL_NAME, ToolObservationFormatter::projectMemory),
            Map.entry("code_execution", ToolObservationFormatter::projectCodeExecution),
            Map.entry("bash", ToolObservationFormatter::projectBash)
    );

    /**
     * 这些工具的成功/失败都属于正常业务结果，failure projector 必须继续工作；
     * 其他工具保持原有契约：ok=false 或 failed=true 时回退完整 JSON。
     */
    private static final Set<String> STATE_AWARE_TOOLS = Set.of(
            "bash", "code_execution", "host_cli", "browser", "agent_browser");

    private static final String[] BINARY_JSON_KEYS = {
            "\"imageBase64\"", "\"image_base64\"", "\"base64Image\"", "\"base64_image\""
    };

    private ToolObservationFormatter() {
    }

    /**
     * 命中返回紧凑文本；未注册、非白名单 ok=false、缺键或异常返回 {@code null}。
     * <p>
     * {@code llmData} 不一定是 Map：deep_search 等工具传的是 POJO，这里统一归一化后再投影。
     */
    public static String format(String toolName, Object llmData) {
        return format(toolName, llmData, false);
    }

    /**
     * 状态感知投影。只有本轮计划覆盖的五个代码型工具会在失败时继续投影，
     * 避免把 ok=false 的完整 JSON 重新塞回模型；其他工具行为保持不变。
     */
    public static String format(String toolName, Object llmData, boolean failed) {
        if (toolName == null || llmData == null) {
            return null;
        }
        Function<Map<?, ?>, String> projector = PROJECTORS.get(toolName);
        if (projector == null) {
            return null;
        }
        boolean stateAware = STATE_AWARE_TOOLS.contains(toolName);
        if ((failed || Boolean.FALSE.equals(llmData instanceof Map<?, ?> map ? map.get("ok") : null))
                && !stateAware) {
            return null;
        }
        Map<?, ?> data = asMap(llmData);
        if (data == null) {
            return null;
        }
        if (failed && isSimpleFailureDetail(data)) {
            String code = text(data.get("code"));
            String message = StringUtils.firstNonBlank(
                    text(data.get("message")), text(data.get("error")),
                    text(data.get("detail")), code);
            if (StringUtils.isBlank(message)) {
                return null;
            }
            return StringUtils.isBlank(code) || message.contains(code)
                    ? "Error: " + message.strip()
                    : "Error: " + code + ": " + message.strip();
        }
        // 非白名单工具保留 softFail JSON 语义；白名单工具直接进入 failure projector。
        if (Boolean.FALSE.equals(data.get("ok")) && !stateAware) {
            return null;
        }
        try {
            return projector.apply(data);
        } catch (Exception e) {
            log.debug("tool observation projection skipped, tool={}", toolName, e);
            return null;
        }
    }

    /**
     * 把 POJO / String 归一化成字段映射；无法归一化时返回 {@code null}，调用方回落 JSON。
     */
    private static Map<?, ?> asMap(Object llmData) {
        if (llmData instanceof Map<?, ?> data) {
            return data;
        }
        if (llmData instanceof String || llmData instanceof Number || llmData instanceof Boolean) {
            return null;
        }
        try {
            Object parsed = JSON.toJSON(llmData);
            return parsed instanceof Map<?, ?> data ? data : null;
        } catch (Exception e) {
            log.debug("tool observation llmData is not map-like, class={}", llmData.getClass().getName(), e);
            return null;
        }
    }

    // ---- workspace_read ----

    private static String projectRead(Map<?, ?> data) {
        String type = text(data.get("type"));
        if ("image".equals(type)) {
            // 元数据本身很短，且模型必须知道这是图片（正文走多模态 media），保留 JSON。
            return null;
        }
        if ("file_unchanged".equals(type)) {
            String stub = text(data.get("message"));
            return StringUtils.isBlank(stub) ? WorkspaceReadTool.FILE_UNCHANGED_STUB : stub;
        }
        String content = text(data.get("content"));
        if (StringUtils.isBlank(content)) {
            return "(file is empty)";
        }
        StringBuilder out = new StringBuilder(content);
        int startLine = intOf(data.get("startLine"), 0);
        int endLine = intOf(data.get("endLine"), 0);
        int totalLines = intOf(data.get("totalLines"), 0);
        if (totalLines > endLine) {
            out.append("\n[file has ").append(totalLines).append(" lines; shown ")
                    .append(startLine).append('-').append(endLine).append(']');
        }
        if (Boolean.TRUE.equals(data.get("truncated"))) {
            out.append("\n[truncated at line ").append(endLine)
                    .append("; call workspace_read with start_line=").append(endLine + 1)
                    .append(" to continue]");
        }
        return out.toString();
    }

    // ---- workspace_grep ----

    private static String projectGrep(Map<?, ?> data) {
        List<?> matches = list(data.get("matches"));
        if (matches.isEmpty()) {
            return "No matches found";
        }
        StringBuilder out = new StringBuilder();
        for (Object item : matches) {
            if (!(item instanceof Map<?, ?> row)) {
                return null;
            }
            String path = text(row.get("path"));
            String lineText = text(row.get("text"));
            if (StringUtils.isBlank(path)) {
                return null;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(path).append(':').append(intOf(row.get("line"), 0)).append(':').append(lineText);
        }
        if (Boolean.TRUE.equals(data.get("truncated"))) {
            out.append("\n[truncated; narrow the pattern or path]");
        }
        return out.toString();
    }

    // ---- workspace_glob ----

    private static String projectGlob(Map<?, ?> data) {
        List<?> files = list(data.get("files"));
        if (files.isEmpty()) {
            return "No files matched";
        }
        StringBuilder out = new StringBuilder();
        for (Object file : files) {
            String path = text(file);
            if (StringUtils.isBlank(path)) {
                return null;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(path);
        }
        if (Boolean.TRUE.equals(data.get("truncated"))) {
            out.append("\n[truncated; use a more specific pattern]");
        }
        return out.toString();
    }

    // ---- workspace_list ----

    private static String projectList(Map<?, ?> data) {
        List<?> entries = list(data.get("entries"));
        if (entries.isEmpty()) {
            return "(empty directory)";
        }
        StringBuilder out = new StringBuilder();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> row)) {
                return null;
            }
            String path = text(row.get("path"));
            if (StringUtils.isBlank(path)) {
                return null;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("DIR".equals(text(row.get("type"))) ? "DIR  " : "FILE ").append(path);
        }
        if (Boolean.TRUE.equals(data.get("truncated"))) {
            out.append("\n[truncated; list a subdirectory or reduce max_depth]");
        }
        return out.toString();
    }

    // ---- workspace_edit ----

    private static String projectEdit(Map<?, ?> data) {
        String path = text(data.get("path"));
        if (StringUtils.isBlank(path)) {
            return null;
        }
        int replacements = intOf(data.get("replacements"), 1);
        StringBuilder out = new StringBuilder()
                .append("The file ").append(path).append(" has been updated successfully. (")
                .append(replacements).append(replacements == 1 ? " replacement)" : " replacements)");
        // diff 是模型确认改动内容的唯一依据，必须原样保留。
        String diff = text(data.get("diff"));
        if (StringUtils.isNotBlank(diff)) {
            out.append("\n\n").append(diff);
        }
        return out.toString();
    }

    // ---- workspace_write ----

    private static String projectWrite(Map<?, ?> data) {
        String path = text(data.get("path"));
        if (StringUtils.isBlank(path)) {
            return null;
        }
        return "File written successfully: " + path;
    }

    // ---- TaskOutput ----

    private static String projectTaskOutput(Map<?, ?> data) {
        StringBuilder out = new StringBuilder(text(data.get("message")));
        // retrieval_status 必须保留：模型靠它区分「任务仍在跑」和「已完成但没输出」。
        // 丢掉它，block=false 时模型会把未终态当成终态，后台子 Agent 的结果被静默丢弃。
        String retrieval = text(data.get("retrieval_status"));
        if (StringUtils.isNotBlank(retrieval)) {
            out.append("\nretrieval_status: ").append(retrieval);
        }
        String output = text(data.get("output"));
        if (StringUtils.isNotBlank(output)) {
            out.append("\n\n").append(output);
        }
        String error = text(data.get("error"));
        if (StringUtils.isNotBlank(error)) {
            out.append("\n\nError: ").append(error);
        }
        return out.length() == 0 ? null : out.toString();
    }

    // ---- session_search ----

    private static String projectSessionSearch(Map<?, ?> data) {
        Map<?, ?> payload = data;
        Object nestedResult = data.get("result");
        if (nestedResult instanceof Map<?, ?> resultMap) {
            payload = resultMap;
        } else if (nestedResult instanceof String raw) {
            try {
                Object parsed = JSON.parse(raw);
                if (parsed instanceof Map<?, ?> resultMap) {
                    payload = resultMap;
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        String mode = text(payload.get("mode"));
        if ("read".equals(mode) || "scroll".equals(mode)) {
            return projectSessionMessages(payload);
        }
        if ("discover".equals(mode)) {
            return projectSessionDiscover(payload);
        }
        if ("browse".equals(mode)) {
            return projectSessionBrowse(payload);
        }
        return null;
    }

    private static final int SESSION_SEARCH_MAX_CHARS = 9_000;
    private static final int SESSION_DISCOVER_FULL_RESULT_LIMIT = 2;
    private static final int SESSION_DISCOVER_FULL_CONTENT_MAX_CHARS = 1_800;
    private static final int SESSION_DISCOVER_EVIDENCE_MAX_CHARS = 1_200;

    private static String projectSessionMessages(Map<?, ?> data) {
        List<?> messages = list(data.get("messages"));
        StringBuilder header = new StringBuilder();
        header.append(text(data.get("mode")))
                .append(" session_id=").append(StringUtils.defaultString(text(data.get("session_id"))))
                .append(" message_count=").append(intOf(data.get("message_count"), messages.size()));
        if (data.get("truncated") != null) {
            header.append(" truncated=").append(data.get("truncated"));
        }
        if (data.get("messages_before") != null) {
            header.append(" messages_before=").append(data.get("messages_before"));
        }
        if (data.get("messages_after") != null) {
            header.append(" messages_after=").append(data.get("messages_after"));
        }
        String prefix = header.toString();
        int totalMessages = intOf(data.get("message_count"), messages.size());
        int maxPerMessage = totalMessages <= 8 ? 1_400 : 100;
        int perMessage = messages.isEmpty()
                ? 0
                : Math.max(120, Math.min(maxPerMessage,
                (SESSION_SEARCH_MAX_CHARS - prefix.length() - 200) / messages.size()));
        String rendered = renderSessionMessages(prefix, messages, perMessage);
        for (int attempt = 0; attempt < 3 && rendered.length() > SESSION_SEARCH_MAX_CHARS; attempt++) {
            perMessage = Math.max(80, perMessage * 3 / 4);
            rendered = renderSessionMessages(prefix, messages, perMessage);
        }
        return rendered.length() <= SESSION_SEARCH_MAX_CHARS
                ? rendered
                : ToolObservationSerializer.truncateForLlm(rendered, SESSION_SEARCH_MAX_CHARS);
    }

    private static String renderSessionMessages(String prefix, List<?> messages, int perMessage) {
        StringBuilder out = new StringBuilder(prefix);
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String role = StringUtils.defaultString(text(row.get("role")));
            String content = StringUtils.defaultString(text(row.get("content")));
            int cap = "USER".equalsIgnoreCase(role)
                    ? Math.max(perMessage, Math.min(1_200, perMessage * 2))
                    : perMessage;
            out.append("\n[id=").append(StringUtils.defaultString(text(row.get("id"))))
                    .append(" seq=").append(StringUtils.defaultString(text(row.get("seq_no"))))
                    .append(" role=").append(role).append("] ")
                    .append(truncate(content.strip(), cap));
        }
        return out.toString();
    }

    private static String projectSessionDiscover(Map<?, ?> data) {
        List<?> results = list(data.get("results"));
        StringBuilder out = new StringBuilder("discover count=")
                .append(intOf(data.get("count"), results.size()));
        String backend = text(data.get("search_backend"));
        if (StringUtils.isNotBlank(backend)) {
            out.append(" backend=").append(backend);
        }
        if (Boolean.TRUE.equals(data.get("fallback"))) {
            out.append(" fallback=true");
            List<?> fallbackTerms = list(data.get("fallback_terms"));
            if (!fallbackTerms.isEmpty()) {
                out.append(" fallback_terms=").append(StringUtils.join(fallbackTerms, ","));
            }
        }
        if (!results.isEmpty()) {
            out.append("\nconflict_policy=Prefer the newest last_active_at as current truth "
                    + "unless the question explicitly asks about an earlier or historical state.");
        }
        List<?> labelEvidence = list(data.get("label_evidence"));
        if (!labelEvidence.isEmpty()) {
            out.append("\nlabel_evidence=ranked matches from the complete retrieved context:");
            for (Object evidenceItem : labelEvidence) {
                if (!(evidenceItem instanceof Map<?, ?> evidenceRow)) {
                    continue;
                }
                out.append("\n  label=").append(text(evidenceRow.get("label")))
                        .append(" score=").append(text(evidenceRow.get("score")))
                        .append(" sample=")
                        .append(truncate(text(evidenceRow.get("sample")), 360));
                if (out.length() >= SESSION_SEARCH_MAX_CHARS) {
                    break;
                }
            }
        }
        for (int index = 0; index < results.size(); index++) {
            Object item = results.get(index);
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            out.append("\n- session=").append(StringUtils.defaultString(text(row.get("session_id"))))
                    .append(" match=").append(StringUtils.defaultString(text(row.get("match_message_id"))))
                    .append(" role=").append(StringUtils.defaultString(text(row.get("matched_role"))));
            String title = text(row.get("title"));
            if (StringUtils.isNotBlank(title)) {
                out.append(" title=").append(truncate(title, 120));
            }
            String lastActiveAt = text(row.get("last_active_at"));
            if (StringUtils.isNotBlank(lastActiveAt)) {
                out.append(" last_active_at=").append(truncate(lastActiveAt, 80));
            }
            Object searchScore = row.get("search_score");
            if (searchScore != null) {
                out.append(" search_score=").append(searchScore);
            }
            if (Boolean.TRUE.equals(row.get("content_truncated"))) {
                out.append(" content_truncated=true");
            }

            String content = text(row.get("content"));
            String snippet = text(row.get("snippet"));
            List<?> evidenceWindows = list(row.get("evidence_windows"));
            boolean useFullContent = index < SESSION_DISCOVER_FULL_RESULT_LIMIT
                    && StringUtils.isNotBlank(content)
                    && content.length() <= SESSION_DISCOVER_FULL_CONTENT_MAX_CHARS;
            if (useFullContent) {
                int remaining = SESSION_SEARCH_MAX_CHARS - out.length() - 32;
                int cap = Math.max(0, Math.min(SESSION_DISCOVER_FULL_CONTENT_MAX_CHARS, remaining));
                if (cap > 0) {
                    String normalized = content.strip();
                    out.append("\n  content: ").append(truncate(normalized, cap));
                    if (normalized.length() > cap) {
                        out.append(" content_truncated_at_observation_budget=true");
                    }
                }
            } else if (!evidenceWindows.isEmpty()) {
                for (int windowIndex = 0; windowIndex < evidenceWindows.size(); windowIndex++) {
                    String evidenceWindow = text(evidenceWindows.get(windowIndex));
                    if (StringUtils.isBlank(evidenceWindow)) {
                        continue;
                    }
                    out.append("\n  evidence_window[").append(windowIndex + 1).append("]: ")
                            .append(truncate(
                                    evidenceWindow.strip(),
                                    SESSION_DISCOVER_EVIDENCE_MAX_CHARS));
                    if (out.length() >= SESSION_SEARCH_MAX_CHARS) {
                        break;
                    }
                }
                if (StringUtils.isNotBlank(content)) {
                    out.append(" content_omitted_large=true");
                }
            } else if (StringUtils.isNotBlank(snippet)) {
                out.append("\n  evidence: ")
                        .append(truncate(snippet.strip(), SESSION_DISCOVER_EVIDENCE_MAX_CHARS));
                if (StringUtils.isNotBlank(content)) {
                    out.append(" content_omitted_large=true");
                }
            }
            if (out.length() >= SESSION_SEARCH_MAX_CHARS) {
                break;
            }
        }
        return out.length() <= SESSION_SEARCH_MAX_CHARS
                ? out.toString()
                : ToolObservationSerializer.truncateForLlm(out.toString(), SESSION_SEARCH_MAX_CHARS);
    }

    private static String projectSessionBrowse(Map<?, ?> data) {
        List<?> results = list(data.get("results"));
        StringBuilder out = new StringBuilder("browse count=")
                .append(intOf(data.get("count"), results.size()));
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            out.append("\n- session=").append(StringUtils.defaultString(text(row.get("session_id"))));
            String title = text(row.get("title"));
            if (StringUtils.isNotBlank(title)) {
                out.append(" title=").append(truncate(title, 120));
            }
            String preview = text(row.get("preview"));
            if (StringUtils.isNotBlank(preview)) {
                out.append("\n  ").append(truncate(preview.strip(), 240));
            }
        }
        return out.toString();
    }

    // ---- WebSearch ----

    /** 单条命中摘要的截断长度：200 会把一句话结论截断，300 能保住大多数摘要的主干。 */
    static final int SNIPPET_MAX_CHARS = 300;

    private static String projectWebSearch(Map<?, ?> data) {
        List<?> hits = list(data.get("hits"));
        StringBuilder out = new StringBuilder();
        String summary = text(data.get("summary"));
        if (StringUtils.isNotBlank(summary)) {
            out.append("Summary:\n").append(summary.strip()).append("\n\n");
        }
        if (hits.isEmpty()) {
            out.append("No results found");
        } else {
            int index = 1;
            for (Object item : hits) {
                if (!(item instanceof Map<?, ?> row)) {
                    return null;
                }
                String url = text(row.get("url"));
                if (StringUtils.isBlank(url)) {
                    return null;
                }
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append('[').append(index++).append("] ").append(StringUtils.defaultString(text(row.get("title"))))
                        .append('\n').append("    ").append(url);
                String snippet = text(row.get("snippet"));
                if (StringUtils.isNotBlank(snippet)) {
                    out.append('\n').append("    ").append(truncate(snippet.strip(), SNIPPET_MAX_CHARS));
                }
            }
        }
        // 引用要求本身就是固定提示，不必作为独立字段重复占位。
        out.append("\n\nCite sources with markdown hyperlinks.");
        return out.toString();
    }

    // ---- WebFetch ----

    private static String projectWebFetch(Map<?, ?> data) {
        String content = text(data.get("content"));
        if (StringUtils.isBlank(content)) {
            return null;
        }
        StringBuilder out = new StringBuilder(content.stripTrailing());
        if (Boolean.TRUE.equals(data.get("degraded"))) {
            out.append("\n[model extract failed; returned truncated page text]");
        }
        String extractError = text(data.get("extractError"));
        if (StringUtils.isNotBlank(extractError)) {
            out.append("\nextract_error: ").append(extractError);
        }
        // 2xx 是常态，只有非成功状态码才值得占位。
        int status = intOf(data.get("status"), 0);
        if (status >= 400) {
            out.append("\nstatus: ").append(status);
        }
        return out.toString();
    }

    // ---- deep_search ----

    private static String projectDeepSearch(Map<?, ?> data) {
        StringBuilder out = new StringBuilder();
        String answer = text(data.get("answerSummary"));
        if (StringUtils.isNotBlank(answer)) {
            out.append(answer.strip());
        }
        appendChapters(out, data.get("chapters"));
        appendSearchResults(out, data.get("results"));
        return out.length() == 0 ? null : out.toString();
    }

    private static void appendChapters(StringBuilder out, Object chapters) {
        if (!(chapters instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        out.append("\n\n## Chapters");
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> chapter)) {
                continue;
            }
            out.append("\n### ").append(StringUtils.defaultString(text(chapter.get("title"))));
            String summary = text(chapter.get("summary"));
            if (StringUtils.isNotBlank(summary)) {
                out.append('\n').append(summary.strip());
            }
        }
    }

    private static void appendSearchResults(StringBuilder out, Object results) {
        if (!(results instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        out.append("\n\n## Sources");
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String query = text(row.get("query"));
            out.append("\n- ").append(StringUtils.defaultString(query));
            if (row.get("docs") instanceof List<?> docs) {
                for (Object doc : docs) {
                    if (!(doc instanceof Map<?, ?> d)) {
                        continue;
                    }
                    String url = text(d.get("url"));
                    if (StringUtils.isBlank(url)) {
                        continue;
                    }
                    out.append("\n    ").append(StringUtils.defaultString(text(d.get("title"))))
                            .append(" — ").append(url);
                }
            }
        }
    }

    // ---- skill_view / skills_search ----

    private static String projectSkillView(Map<?, ?> data) {
        String content = text(data.get("content"));
        if (StringUtils.isBlank(content)) {
            return null;
        }
        StringBuilder out = new StringBuilder(content.stripTrailing());
        if (Boolean.TRUE.equals(data.get("is_binary"))) {
            out.append("\n[binary file]");
        }
        String file = text(data.get("file"));
        if (StringUtils.isNotBlank(file)) {
            out.insert(0, file + "\n\n");
        }
        return out.toString();
    }

    private static String projectSkillsSearch(Map<?, ?> data) {
        List<?> items = list(data.get("items"));
        if (items.isEmpty()) {
            return "No skills matched";
        }
        StringBuilder out = new StringBuilder();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> row)) {
                return null;
            }
            String name = text(row.get("name"));
            if (StringUtils.isBlank(name)) {
                return null;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("- ").append(name);
            String description = text(row.get("description"));
            if (StringUtils.isNotBlank(description)) {
                out.append(": ").append(truncate(description.strip(), SNIPPET_MAX_CHARS));
            }
        }
        return out.toString();
    }

    // ---- Agent / SendMessage / TaskStop ----

    private static String projectAgent(Map<?, ?> data) {
        String content = text(data.get("content"));
        String agentId = text(data.get("agentId"));
        String errorMsg = text(data.get("errorMsg"));
        StringBuilder out = new StringBuilder();
        if (StringUtils.isNotBlank(content)) {
            out.append(content.strip());
        } else if (StringUtils.isBlank(errorMsg)) {
            out.append("(Subagent completed but returned no output.)");
        }
        if (StringUtils.isNotBlank(errorMsg)) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("Error: ").append(errorMsg);
        }
        // agentId 是续跑入口：模型要靠它调 SendMessage，必须给可执行提示而不是裸 id。
        if (StringUtils.isNotBlank(agentId)) {
            out.append("\n\nagentId: ").append(agentId)
                    .append(" (use SendMessage with to: '").append(agentId)
                    .append("' to continue this agent)");
        }
        String status = text(data.get("status"));
        if (StringUtils.isNotBlank(status)) {
            out.append("\nstatus: ").append(status);
        }
        Object toolUses = data.get("totalToolUseCount");
        if (toolUses != null) {
            out.append("\ntool_uses: ").append(toolUses);
        }
        if (Boolean.TRUE.equals(data.get("run_in_background"))) {
            out.append("\nrun_in_background: true");
            // 后台派发时 agentId 是后续 SendMessage/TaskStop 的句柄；结果会自动回传。
            if (StringUtils.isNotBlank(agentId)) {
                out.append("\n(agent_id: '").append(agentId)
                        .append("'; result will be returned automatically when the task completes)");
            }
        }
        return out.toString();
    }

    private static String projectSendMessage(Map<?, ?> data) {
        String message = text(data.get("message"));
        if (StringUtils.isBlank(message)) {
            return null;
        }
        StringBuilder out = new StringBuilder(message);
        String agentId = text(data.get("agentId"));
        if (StringUtils.isNotBlank(agentId)) {
            out.append("\nagentId: ").append(agentId);
        }
        if (Boolean.TRUE.equals(data.get("queued"))) {
            out.append("\nqueued: true");
        }
        return out.toString();
    }

    private static String projectTaskStop(Map<?, ?> data) {
        String message = text(data.get("message"));
        if (StringUtils.isBlank(message)) {
            return null;
        }
        StringBuilder out = new StringBuilder(message);
        String agentId = text(data.get("agent_id"));
        if (StringUtils.isNotBlank(agentId)) {
            out.append("\nagent_id: ").append(agentId);
        }
        String status = text(data.get("status"));
        if (StringUtils.isNotBlank(status)) {
            out.append("\nstatus: ").append(status);
        }
        return out.toString();
    }

    // ---- TaskCreate / TaskUpdate / TaskGet / TaskList / TodoWrite ----

    private static String projectTaskRecord(Map<?, ?> data) {
        Object task = data.get("task");
        if (!(task instanceof Map<?, ?> row)) {
            return text(data.get("message"));
        }
        String rendered = renderTask(row);
        String message = text(data.get("message"));
        return StringUtils.isBlank(message) ? rendered : message + "\n" + rendered;
    }

    private static String projectTaskList(Map<?, ?> data) {
        List<?> tasks = list(data.get("tasks"));
        if (tasks.isEmpty()) {
            String message = text(data.get("message"));
            return StringUtils.isBlank(message) ? "(no tasks)" : message;
        }
        StringBuilder out = new StringBuilder();
        for (Object item : tasks) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(renderTask(row));
        }
        return out.toString();
    }

    /** 任务行只保留 id / 状态 / 标题 / 依赖，其余编排元数据模型用不上。 */
    private static String renderTask(Map<?, ?> row) {
        String id = StringUtils.firstNonBlank(text(row.get("id")), text(row.get("task_id")));
        String status = text(row.get("status"));
        String subject = StringUtils.firstNonBlank(text(row.get("subject")), text(row.get("description")));
        StringBuilder out = new StringBuilder("- ");
        if (StringUtils.isNotBlank(id)) {
            out.append('[').append(id).append("] ");
        }
        if (StringUtils.isNotBlank(status)) {
            out.append(status).append(' ');
        }
        out.append(StringUtils.defaultString(subject));
        Object blockedBy = row.get("blockedBy");
        if (blockedBy instanceof List<?> deps && !deps.isEmpty()) {
            out.append(" (blockedBy: ").append(StringUtils.join(deps, ", ")).append(')');
        }
        return out.toString();
    }

    // ---- EnterPlanMode / ExitPlanMode ----

    private static String projectEnterPlanMode(Map<?, ?> data) {
        // instructions 已由 PlanModePromptInjector 注入 system prompt，工具结果里再塞一份等于双份全文。
        String message = text(data.get("message"));
        if (StringUtils.isBlank(message)) {
            return null;
        }
        StringBuilder out = new StringBuilder(message);
        String planFilePath = text(data.get("planFilePath"));
        if (StringUtils.isNotBlank(planFilePath)) {
            out.append("\nplanFilePath: ").append(planFilePath);
        }
        return out.toString();
    }

    private static String projectExitPlanMode(Map<?, ?> data) {
        // message 里已含 approved / feedback / restoredMode 与计划正文，plan 字段是第二份同样的内容。
        String message = text(data.get("message"));
        if (StringUtils.isBlank(message)) {
            return null;
        }
        return message.stripTrailing();
    }

    // ---- AskUserQuestion / RequestDesktopControl ----

    private static String projectAskUserQuestion(Map<?, ?> data) {
        StringBuilder out = new StringBuilder();
        String message = text(data.get("message"));
        if (StringUtils.isNotBlank(message)) {
            out.append(message);
        }
        if (data.get("answers") != null) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("answers: ").append(JSON.toJSONString(data.get("answers")));
        }
        String questionId = text(data.get("questionId"));
        if (StringUtils.isNotBlank(questionId)) {
            out.append("\nquestionId: ").append(questionId);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String projectDesktopControl(Map<?, ?> data) {
        String message = text(data.get("message"));
        if (StringUtils.isNotBlank(message)) {
            return message;
        }
        StringBuilder out = new StringBuilder();
        String status = text(data.get("status"));
        if (StringUtils.isNotBlank(status)) {
            out.append("status: ").append(status);
        }
        String reason = text(data.get("reason"));
        if (StringUtils.isNotBlank(reason)) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("reason: ").append(reason);
        }
        return out.length() == 0 ? null : out.toString();
    }

    // ---- canvas ----

    private static String projectCanvasPublish(Map<?, ?> data) {
        String message = text(data.get("message"));
        if (StringUtils.isBlank(message)) {
            return null;
        }
        StringBuilder out = new StringBuilder(message);
        String relativePath = text(data.get("relativePath"));
        if (StringUtils.isNotBlank(relativePath)) {
            out.append('\n').append(relativePath);
        }
        String title = text(data.get("title"));
        if (StringUtils.isNotBlank(title)) {
            out.append("\ntitle: ").append(title);
        }
        if (Boolean.TRUE.equals(data.get("salvaged"))) {
            out.append("\n[html salvaged]");
        }
        return out.toString();
    }

    // ---- ToolSearch / ToolDescribe / memory ----

    private static String projectToolSearch(Map<?, ?> data) {
        List<?> matches = list(data.get("matches"));
        StringBuilder out = new StringBuilder();
        if (matches.isEmpty()) {
            out.append("No tools matched.");
        } else {
            for (Object item : matches) {
                if (!(item instanceof Map<?, ?> row)) {
                    return null;
                }
                String name = text(row.get("name"));
                if (StringUtils.isBlank(name)) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append("- ").append(name);
                String description = text(row.get("description"));
                if (StringUtils.isNotBlank(description)) {
                    out.append(": ").append(truncate(description.strip(), SNIPPET_MAX_CHARS));
                }
            }
        }
        String hint = text(data.get("hint"));
        if (StringUtils.isNotBlank(hint)) {
            out.append("\n\n").append(hint);
        }
        return out.toString();
    }

    private static String projectToolDescribe(Map<?, ?> data) {
        String description = text(data.get("description"));
        if (StringUtils.isBlank(description)) {
            return null;
        }
        StringBuilder out = new StringBuilder(description.strip());
        Object parameters = data.get("parameters");
        if (parameters != null) {
            out.append("\n\nparameters: ").append(JSON.toJSONString(parameters));
        }
        return out.toString();
    }

    private static String projectMemory(Map<?, ?> data) {
        String message = text(data.get("message"));
        StringBuilder out = new StringBuilder(StringUtils.defaultString(message));
        Object results = data.get("results");
        if (results instanceof List<?> items && !items.isEmpty()) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(JSON.toJSONString(items));
        }
        if (Boolean.TRUE.equals(data.get("staged"))) {
            out.append("\n[staged]");
        }
        if (Boolean.TRUE.equals(data.get("no_change"))) {
            out.append("\n[no change]");
        }
        return out.length() == 0 ? null : out.toString();
    }

    // ---- code_execution / bash ----

    private static String projectCodeExecution(Map<?, ?> data) {
        StringBuilder out = new StringBuilder();
        String stdout = text(data.get("stdout"));
        String stderr = text(data.get("stderr"));
        if (StringUtils.isNotBlank(stdout)) {
            out.append(stdout.stripTrailing());
        }
        if (StringUtils.isNotBlank(stderr)) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("stderr:\n").append(stderr.stripTrailing());
        }
        // 失败时 error 才是模型要读的；成功时它恒为空。
        String error = text(data.get("error"));
        if (StringUtils.isNotBlank(error)) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("Error: ").append(error.stripTrailing());
        }
        if (StringUtils.isBlank(error) && !Boolean.TRUE.equals(data.get("ok"))) {
            String status = text(data.get("status"));
            if (StringUtils.isNotBlank(status) && !"ok".equalsIgnoreCase(status)) {
                out.append(out.length() == 0 ? "" : "\n\n")
                        .append("status: ").append(status);
            }
        }
        appendDistinctResult(out, data, stdout);
        appendDeletedPaths(out, data.get("deleted_files"));
        appendWorkspaceSyncIssues(out, data.get("workspace_sync"));
        // code_execution 的 url 是 document_generate / image block 的既有兼容契约：
        // 只保留 produced_files 上的一个规范 url，其他文件元数据仍只留在 toolResult。
        appendPaths(out, data.get("produced_files"), "files", true);
        return out.length() == 0 ? "(no output)" : out.toString();
    }

    private static String projectBash(Map<?, ?> data) {
        StringBuilder out = new StringBuilder();
        String stdout = text(data.get("stdout"));
        String stderr = text(data.get("stderr"));
        if (StringUtils.isNotBlank(stdout)) {
            out.append(stdout.stripTrailing());
        }
        if (StringUtils.isNotBlank(stderr)) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("stderr:\n").append(stderr.stripTrailing());
        }
        appendIfNotZero(out, data, "exit_code", "exit_code");
        if (Boolean.TRUE.equals(data.get("timed_out"))) {
            out.append("\n[timed out]");
        }
        if (Boolean.TRUE.equals(data.get("truncated"))) {
            out.append("\n[output truncated]");
        }
        appendError(out, data.get("error"));
        appendDeletedPaths(out, data.get("deleted_files"));
        appendWorkspaceSyncIssues(out, data.get("workspace_sync"));
        appendPaths(out, data.get("produced_files"), "files");
        return out.length() == 0 ? "(no output)" : out.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max).stripTrailing() + "…";
    }

    // ---- host_cli / browser ----

    private static String projectBrowser(Map<?, ?> data) {
        String stdout = sanitizeBrowserStdout(text(data.get("stdout")));
        return projectCli(data, stdout);
    }

    private static String projectCli(Map<?, ?> data) {
        return projectCli(data, text(data.get("stdout")));
    }

    private static String projectCli(Map<?, ?> data, String stdout) {
        StringBuilder out = new StringBuilder();
        String stderr = text(data.get("stderr"));
        if (StringUtils.isNotBlank(stdout)) {
            out.append(stdout.stripTrailing());
        }
        if (StringUtils.isNotBlank(stderr)) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("stderr:\n").append(stderr.stripTrailing());
        }
        // 只有非默认值才值得占 token；正常退出、未超时、未截断都不说。
        appendIfNotZero(out, data, "exit_code", "exit_code");
        if (Boolean.TRUE.equals(data.get("timed_out"))) {
            out.append("\n[timed out]");
        }
        if (Boolean.TRUE.equals(data.get("truncated"))) {
            out.append("\n[output truncated]");
        }
        appendError(out, data.get("error"));
        String errorCode = text(data.get("error_code"));
        if (StringUtils.isNotBlank(errorCode)
                && (out.length() == 0 || !out.toString().contains(errorCode))) {
            out.append(out.length() == 0 ? "" : "\n")
                    .append("error_code: ").append(errorCode);
        }
        String hint = text(data.get("error_hint"));
        if (StringUtils.isNotBlank(hint)) {
            out.append("\nhint: ").append(hint);
        }
        String retry = text(data.get("retry"));
        if (StringUtils.isNotBlank(retry)) {
            out.append("\nretry: ").append(retry);
        }
        // 会话重建提示（agent_browser）：模型据此知道 CDP 会话是重建的，需要重新导航。
        String notice = text(data.get("notice"));
        if (StringUtils.isNotBlank(notice)) {
            out.append("\nnotice: ").append(notice);
        }
        if (Boolean.TRUE.equals(data.get("image_upload_failed"))) {
            String message = text(data.get("message"));
            out.append("\n[image upload failed]");
            if (StringUtils.isNotBlank(message)) {
                out.append(' ').append(message.strip());
            }
        }
        // 产物引用必须保留：browser 的 screenshot 把 PNG 写进工作区，工具描述让模型用
        // workspace_read 读相对路径 —— 吞掉 artifacts 就等于让模型看不了截的图。
        appendPaths(out, data.get("artifacts"), "files");
        appendArtifactFailures(out, data.get("artifact_failures"));
        return out.length() == 0 ? "(no output)" : out.toString();
    }

    /** 产物列表只把相对路径摊给模型，URL / size 等留在 toolResult 给前端用。 */
    private static void appendPaths(StringBuilder out, Object artifacts, String label) {
        appendPaths(out, artifacts, label, false);
    }

    private static void appendPaths(StringBuilder out,
                                    Object artifacts,
                                    String label,
                                    boolean includeUrl) {
        if (!(artifacts instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        out.append('\n').append(label).append(':');
        for (Object item : items) {
            if (item instanceof Map<?, ?> row) {
                // code_execution / image_generation 用 snake_case 的 file_name，workspace 产物用驼峰。
                String path = StringUtils.firstNonBlank(
                        text(row.get("relativePath")),
                        text(row.get("relative_path")),
                        text(row.get("fileName")),
                        text(row.get("file_name")));
                if (StringUtils.isNotBlank(path)) {
                    out.append("\n- ").append(path);
                    String url = includeUrl ? text(row.get("url")) : null;
                    if (StringUtils.isNotBlank(url) && !out.toString().contains(url)) {
                        out.append(" (url: ").append(url).append(')');
                    }
                }
            }
        }
    }

    private static void appendArtifactFailures(StringBuilder out, Object failures) {
        if (!(failures instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        out.append("\nfailed files:");
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String path = StringUtils.firstNonBlank(
                    text(row.get("relativePath")),
                    text(row.get("relative_path")),
                    text(row.get("fileName")),
                    text(row.get("file_name")));
            String reason = text(row.get("reason"));
            String message = text(row.get("message"));
            if (StringUtils.isAllBlank(path, reason, message)) {
                continue;
            }
            out.append("\n- ").append(StringUtils.defaultIfBlank(path, "(unlocated artifact)"));
            if (StringUtils.isNotBlank(reason)) {
                out.append(": ").append(reason);
            }
            if (StringUtils.isNotBlank(message) && !message.equals(reason)) {
                out.append(StringUtils.isBlank(reason) ? ": " : " - ")
                        .append(truncate(message.strip(), 300));
            }
        }
    }

    private static void appendDeletedPaths(StringBuilder out, Object deletedFiles) {
        if (!(deletedFiles instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        out.append("\ndeleted:");
        for (Object item : items) {
            String path = item instanceof Map<?, ?> row
                    ? StringUtils.firstNonBlank(
                    text(row.get("relativePath")),
                    text(row.get("relative_path")),
                    text(row.get("path")))
                    : text(item);
            if (StringUtils.isNotBlank(path)) {
                out.append("\n- ").append(path);
            }
        }
    }

    private static void appendDistinctResult(StringBuilder out, Map<?, ?> data, String stdout) {
        Object rawResult = data.get("result");
        if (rawResult == null) {
            return;
        }
        String result = rawResult instanceof String text ? text : JSON.toJSONString(rawResult);
        if (StringUtils.isBlank(result)
                || StringUtils.equals(result.strip(), StringUtils.defaultString(stdout).strip())
                || (StringUtils.isNotBlank(stdout) && stdout.contains(result.strip()))) {
            return;
        }
        out.append(out.length() == 0 ? "" : "\n\n")
                .append("result: ").append(result.stripTrailing());
    }

    private static void appendWorkspaceSyncIssues(StringBuilder out, Object rawSync) {
        if (!(rawSync instanceof Map<?, ?> sync)) {
            return;
        }
        String status = text(sync.get("status"));
        List<?> conflicts = list(sync.get("conflicts"));
        boolean abnormal = (StringUtils.isNotBlank(status) && !"ok".equalsIgnoreCase(status))
                || !conflicts.isEmpty();
        if (abnormal) {
            out.append("\nworkspace_sync: ")
                    .append(StringUtils.defaultIfBlank(status, "failed"));
            String errorCode = text(sync.get("errorCode"));
            if (StringUtils.isNotBlank(errorCode)) {
                out.append(" errorCode=").append(errorCode);
            }
            int count = 0;
            for (Object conflict : conflicts) {
                if (!(conflict instanceof Map<?, ?> row) || count++ >= 5) {
                    continue;
                }
                String code = text(row.get("code"));
                String path = text(row.get("path"));
                String message = text(row.get("message"));
                out.append("\n- ")
                        .append(StringUtils.defaultIfBlank(code, "workspace_conflict"));
                if (StringUtils.isNotBlank(path)) {
                    out.append(": ").append(path);
                }
                if (StringUtils.isNotBlank(message)) {
                    out.append(" - ").append(truncate(message, 300));
                }
            }
            List<?> warnings = list(sync.get("warnings"));
            for (int index = 0; index < Math.min(warnings.size(), 5); index++) {
                String warning = text(warnings.get(index));
                if (StringUtils.isNotBlank(warning)) {
                    out.append("\nsync warning: ").append(truncate(warning, 300));
                }
            }
            return;
        }
        List<?> skipped = list(sync.get("skippedLargeFiles"));
        if (!skipped.isEmpty()) {
            out.append("\nworkspace_sync skipped large files:");
            for (int index = 0; index < Math.min(skipped.size(), 10); index++) {
                out.append("\n- ").append(skipped.get(index));
            }
        }
    }

    private static void appendError(StringBuilder out, Object rawError) {
        String error = text(rawError);
        if (StringUtils.isBlank(error) || out.toString().contains(error.strip())) {
            return;
        }
        out.append(out.length() == 0 ? "" : "\n\n")
                .append("Error: ").append(error.stripTrailing());
    }

    private static boolean isSimpleFailureDetail(Map<?, ?> data) {
        return data.get("stdout") == null
                && data.get("stderr") == null
                && data.get("error") == null
                && data.get("exit_code") == null
                && data.get("timed_out") == null
                && data.get("artifacts") == null
                && data.get("artifact_failures") == null
                && data.get("produced_files") == null
                && data.get("result") == null;
    }

    private static String sanitizeBrowserStdout(String stdout) {
        if (StringUtils.isBlank(stdout)) {
            return stdout;
        }
        StringBuilder out = new StringBuilder(stdout.length());
        int cursor = 0;
        while (cursor < stdout.length()) {
            int keyStart = indexOfBinaryKey(stdout, cursor);
            int dataUrlStart = indexOfIgnoreCase(stdout, "data:image/", cursor);
            int start = firstNonNegative(keyStart, dataUrlStart);
            if (start < 0) {
                out.append(stdout, cursor, stdout.length());
                break;
            }
            out.append(stdout, cursor, start);
            if (start == keyStart) {
                int valueStart = jsonValueStart(stdout, keyStart);
                if (valueStart >= 0 && stdout.charAt(valueStart) == '"') {
                    int valueEnd = jsonStringEnd(stdout, valueStart + 1);
                    if (valueEnd >= 0) {
                        out.append(stdout, keyStart, valueStart + 1).append("[omitted]");
                        cursor = valueEnd;
                        continue;
                    }
                }
            } else {
                int marker = indexOfIgnoreCase(stdout, ";base64,", dataUrlStart + 10);
                if (marker >= 0) {
                    int valueStart = marker + ";base64,".length();
                    int valueEnd = base64End(stdout, valueStart);
                    out.append(stdout, dataUrlStart, valueStart).append("[omitted]");
                    cursor = valueEnd;
                    continue;
                }
            }
            // 只是长得像 key / data URL 的普通文本：保留一个字符后继续扫描。
            out.append(stdout.charAt(start));
            cursor = start + 1;
        }
        return out.toString();
    }

    private static int indexOfBinaryKey(String text, int from) {
        int found = -1;
        for (String key : BINARY_JSON_KEYS) {
            int index = indexOfIgnoreCase(text, key, from);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }

    private static int jsonValueStart(String text, int keyStart) {
        int colon = text.indexOf(':', keyStart + 1);
        if (colon < 0) {
            return -1;
        }
        int cursor = colon + 1;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor < text.length() ? cursor : -1;
    }

    private static int jsonStringEnd(String text, int from) {
        boolean escaped = false;
        for (int index = from; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return index;
            }
        }
        return -1;
    }

    private static int base64End(String text, int from) {
        int cursor = from;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if ((current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '+' || current == '/' || current == '='
                    || current == '\r' || current == '\n') {
                cursor++;
                continue;
            }
            break;
        }
        return cursor;
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int max = text.length() - needle.length();
        for (int index = Math.max(0, from); index <= max; index++) {
            if (text.regionMatches(true, index, needle, 0, needle.length())) {
                return index;
            }
        }
        return -1;
    }

    private static int firstNonNegative(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    private static void appendIfNotZero(StringBuilder out, Map<?, ?> data, String key, String label) {
        int value = intOf(data.get(key), 0);
        if (value != 0) {
            out.append('\n').append(label).append(": ").append(value);
        }
    }

    // ---- helpers ----

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.isNotBlank(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }
}
