package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationFormatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果投影契约：模型只看紧凑文本，UI / 账本仍拿完整 JSON。
 * 未注册、非 Map、ok=false、缺键、异常一律回落 null。
 */
public class ToolObservationFormatterTest {

    @Test
    public void unregisteredToolFallsBackToJson() {
        Assert.assertNull(ToolObservationFormatter.format("some_mcp_tool", Map.of("anything", 1)));
    }

    @Test
    public void nonMapPayloadFallsBackToJson() {
        Assert.assertNull(ToolObservationFormatter.format("workspace_list", "plain text"));
        Assert.assertNull(ToolObservationFormatter.format("workspace_list", null));
    }

    @Test
    public void softFailFallsBackToJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("path", "src/a.ts");
        Assert.assertNull(ToolObservationFormatter.format("workspace_write", data));
    }

    @Test
    public void nullToolNameFallsBackToJson() {
        Assert.assertNull(ToolObservationFormatter.format(null, Map.of("path", "a.ts")));
    }

    @Test
    public void missingRequiredKeyFallsBackToJson() {
        Assert.assertNull(ToolObservationFormatter.format("workspace_write", Map.of("ok", true)));
        Assert.assertNull(ToolObservationFormatter.format("workspace_edit", Map.of("ok", true)));
    }

    @Test
    public void registeredToolReturnsCompactText() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("path", "src/a.ts");
        data.put("chars", 1200);
        data.put("message", "已写入文件");

        String projected = ToolObservationFormatter.format("workspace_write", data);

        Assert.assertEquals("File written successfully: src/a.ts", projected);
        Assert.assertFalse(projected.contains("\"chars\""));
        Assert.assertFalse(projected.contains("已写入文件"));
    }

    @Test
    public void grepUsesPathLineTextFormat() {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("path", "src/Foo.java");
        hit.put("line", 7);
        hit.put("text", "    public void handle() {");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("matches", List.of(hit));

        String projected = ToolObservationFormatter.format("workspace_grep", data);

        Assert.assertEquals("src/Foo.java:7:    public void handle() {", projected);
    }

    @Test
    public void emptyGrepSaysNoMatches() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("matches", List.of());

        Assert.assertEquals("No matches found", ToolObservationFormatter.format("workspace_grep", data));
    }

    @Test
    public void readKeepsLineNumberPrefixAndDropsMetadata() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("type", "text");
        data.put("path", "src/Demo.java");
        data.put("startLine", 1);
        data.put("endLine", 2);
        data.put("numLines", 2);
        data.put("totalLines", 2);
        data.put("content", "1 | class Demo {\n2 | }");

        String projected = ToolObservationFormatter.format("workspace_read", data);

        Assert.assertTrue(projected.startsWith("1 | class Demo {"));
        Assert.assertFalse(projected.contains("\"numLines\""));
        Assert.assertFalse(projected.contains("\"totalLines\""));
        // 全文已读完，不再追加行数提示。
        Assert.assertFalse(projected.contains("file has"));
    }

    @Test
    public void readAppendsLineCountWhenNotFullyRead() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("type", "text");
        data.put("startLine", 1);
        data.put("endLine", 10);
        data.put("totalLines", 120);
        data.put("content", "1 | a");

        String projected = ToolObservationFormatter.format("workspace_read", data);

        Assert.assertTrue(projected.contains("[file has 120 lines; shown 1-10]"));
    }

    @Test
    public void readUnchangedStubIsPlainText() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("type", "file_unchanged");
        data.put("message", "File unchanged since last read.");

        Assert.assertEquals("File unchanged since last read.",
                ToolObservationFormatter.format("workspace_read", data));
    }

    @Test
    public void readImageFallsBackToJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("type", "image");
        data.put("path", "a.png");

        Assert.assertNull(ToolObservationFormatter.format("workspace_read", data));
    }

    @Test
    public void listDropsBytesAndKeepsTypePrefix() {
        Map<String, Object> dir = new LinkedHashMap<>();
        dir.put("type", "DIR");
        dir.put("path", "src");
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("type", "FILE");
        file.put("path", "src/App.tsx");
        file.put("bytes", 1201);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("entries", List.of(dir, file));

        String projected = ToolObservationFormatter.format("workspace_list", data);

        Assert.assertEquals("DIR  src\nFILE src/App.tsx", projected);
        Assert.assertFalse(projected.contains("bytes"));
    }

    @Test
    public void editKeepsDiffAndDropsCharCounts() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("path", "src/Main.java");
        data.put("replacements", 1);
        data.put("charsBefore", 100);
        data.put("charsAfter", 101);
        data.put("diff", "--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-a\n+b");

        String projected = ToolObservationFormatter.format("workspace_edit", data);

        Assert.assertTrue(projected.startsWith(
                "The file src/Main.java has been updated successfully. (1 replacement)"));
        Assert.assertTrue(projected.contains("@@ -1 +1 @@"));
        Assert.assertFalse(projected.contains("charsBefore"));
        Assert.assertFalse(projected.contains("charsAfter"));
    }

    @Test
    public void taskOutputDropsDuplicateResultKey() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("message", "任务已完成。");
        data.put("output", "done");

        String projected = ToolObservationFormatter.format("TaskOutput", data);

        Assert.assertTrue(projected.contains("done"));
        Assert.assertEquals(projected.indexOf("done"), projected.lastIndexOf("done"));
    }

    @Test
    public void cliOmitsDefaultFlags() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("stdout", "hello\n");
        data.put("stderr", "");
        data.put("exit_code", 0);
        data.put("timed_out", false);
        data.put("truncated", false);
        data.put("duration_ms", 42);

        String projected = ToolObservationFormatter.format("host_cli", data);

        Assert.assertEquals("hello", projected);
        Assert.assertFalse(projected.contains("exit_code"));
        Assert.assertFalse(projected.contains("duration_ms"));
    }

    @Test
    public void cliReportsNonDefaultFlags() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("stdout", "");
        data.put("stderr", "boom");
        data.put("exit_code", 1);
        data.put("timed_out", true);

        String projected = ToolObservationFormatter.format("browser", data);

        Assert.assertTrue(projected.contains("stderr:\nboom"));
        Assert.assertTrue(projected.contains("exit_code: 1"));
        Assert.assertTrue(projected.contains("[timed out]"));
    }

    @Test
    public void taskOutputKeepsRetrievalStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("message", "任务仍在运行。");
        data.put("retrieval_status", "not_ready");

        String projected = ToolObservationFormatter.format("TaskOutput", data);

        // 模型靠 retrieval_status 区分「还在跑」和「已完成但没输出」；丢掉会误判后台任务已终态。
        Assert.assertTrue(projected.contains("retrieval_status: not_ready"));
    }

    @Test
    public void cliKeepsArtifactPaths() {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("relativePath", "screenshot-1730000000.png");
        artifact.put("previewUrl", "https://example.com/p");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("stdout", "");
        data.put("artifacts", List.of(artifact));

        String projected = ToolObservationFormatter.format("browser", data);

        // browser 的工具描述让模型用 workspace_read 读相对路径，吞掉它就看不了截的图。
        Assert.assertTrue(projected.contains("screenshot-1730000000.png"));
    }

    @Test
    public void cliKeepsRebuiltSessionNotice() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("stdout", "opened");
        data.put("notice", "Agent 浏览器已重建，之前的页面状态已丢失。");

        Assert.assertTrue(ToolObservationFormatter.format("agent_browser", data)
                .contains("Agent 浏览器已重建"));
    }

    // ---- 新增覆盖：检索类 ----

    @Test
    public void webSearchDropsEchoAndTruncatesSnippet() {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("title", "Title A");
        hit.put("url", "https://a.example");
        hit.put("snippet", "s".repeat(1000));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "web_search");
        data.put("ok", Boolean.TRUE);
        data.put("query", "模型刚发过的查询");
        data.put("provider", "exa");
        data.put("durationSec", 3.49);
        data.put("reminder", "Cite sources with markdown hyperlinks in the final answer.");
        data.put("hits", List.of(hit));

        String projected = ToolObservationFormatter.format("WebSearch", data);

        Assert.assertTrue(projected.contains("https://a.example"));
        // query / provider / durationSec 是入参回显与计时，不进上下文。
        Assert.assertFalse(projected.contains("模型刚发过的查询"));
        Assert.assertFalse(projected.contains("durationSec"));
        Assert.assertFalse(projected.contains("provider"));
        // snippet 保留但截断，引用要求折成一行页脚。
        Assert.assertTrue(projected.contains("…"));
        Assert.assertEquals(1, countOccurrences(projected, "Cite sources"));
    }

    @Test
    public void webSearchEmptyHitsSaysNoResults() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("hits", List.of());

        Assert.assertTrue(ToolObservationFormatter.format("WebSearch", data).contains("No results found"));
    }

    @Test
    public void sessionSearchReadKeepsLateUserFactAndCompactsLongAssistantText() {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("id", 1);
        assistant.put("seq_no", 0);
        assistant.put("role", "ASSISTANT");
        assistant.put("content", "a".repeat(12_000));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", 2);
        user.put("seq_no", 1);
        user.put("role", "USER");
        user.put("content", "By the way, my cat's name is Luna.");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("mode", "read");
        data.put("session_id", "s1");
        data.put("message_count", 2);
        data.put("truncated", false);
        data.put("messages", List.of(assistant, user));

        String projected = ToolObservationFormatter.format("session_search", data);

        Assert.assertTrue(projected.contains("my cat's name is Luna"));
        Assert.assertFalse(projected.contains("a".repeat(8_000)));
        Assert.assertTrue(projected.length() < 10_000);
    }

    @Test
    public void sessionSearchDiscoverPreservesAnchorWithoutEchoingArguments() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", "s1");
        result.put("match_message_id", 42);
        result.put("matched_role", "USER");
        result.put("title", "History");
        result.put("snippet", "the cat's name is Luna");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("tool", "session_search");
        data.put("query", "cat name");
        data.put("mode", "discover");
        data.put("count", 1);
        data.put("results", List.of(result));

        String projected = ToolObservationFormatter.format("session_search", data);

        Assert.assertTrue(projected.contains("session=s1"));
        Assert.assertTrue(projected.contains("match=42"));
        Assert.assertTrue(projected.contains("Luna"));
        Assert.assertFalse(projected.contains("\"query\""));
    }

    @Test
    public void sessionSearchDiscoverProjectsFullTopContentAndSnippetForRest() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("session_id", "s1");
        first.put("match_message_id", 41);
        first.put("matched_role", "USER");
        first.put("last_active_at", "2026-11-09T10:00:00Z");
        first.put("search_score", 8.5);
        first.put("content", "The current address is 18 River Street.");
        first.put("snippet", "current address");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("session_id", "s2");
        second.put("match_message_id", 42);
        second.put("matched_role", "USER");
        second.put("content", "The current employer is Northstar Labs.");
        Map<String, Object> third = new LinkedHashMap<>();
        third.put("session_id", "s3");
        third.put("match_message_id", 43);
        third.put("matched_role", "USER");
        third.put("content", "FULL_THIRD_RESULT_SHOULD_NOT_BE_PROJECTED");
        third.put("snippet", "third result snippet");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("mode", "discover");
        data.put("count", 3);
        data.put("search_backend", "token-scan");
        data.put("fallback", Boolean.TRUE);
        data.put("fallback_terms", List.of("address", "employer"));
        data.put("results", List.of(first, second, third));

        String projected = ToolObservationFormatter.format("session_search", data);

        Assert.assertTrue(projected.contains("content: The current address is 18 River Street."));
        Assert.assertTrue(projected.contains("content: The current employer is Northstar Labs."));
        Assert.assertFalse(projected.contains("FULL_THIRD_RESULT_SHOULD_NOT_BE_PROJECTED"));
        Assert.assertTrue(projected.contains("evidence: third result snippet"));
        Assert.assertTrue(projected.contains("last_active_at=2026-11-09T10:00:00Z"));
        Assert.assertTrue(projected.contains("search_score=8.5"));
        Assert.assertTrue(projected.contains("fallback=true"));
        Assert.assertTrue(projected.contains("fallback_terms=address,employer"));
        Assert.assertTrue(projected.contains("Prefer the newest last_active_at"));
    }

    @Test
    public void sessionSearchDiscoverTruncatesOversizedContentWithinBudget() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", "s1");
        result.put("match_message_id", 42);
        result.put("matched_role", "USER");
        result.put("content_truncated", Boolean.TRUE);
        result.put("content", "PREFIX_SHOULD_NOT_BE_PROJECTED" + "x".repeat(20_000));
        result.put("snippet", "target fact with serial 307");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("mode", "discover");
        data.put("count", 1);
        data.put("results", List.of(result));

        String projected = ToolObservationFormatter.format("session_search", data);

        Assert.assertTrue(projected.length() <= 9_000);
        Assert.assertTrue(projected.contains("content_truncated=true"));
        Assert.assertTrue(projected.contains("evidence: target fact with serial 307"));
        Assert.assertTrue(projected.contains("content_omitted_large=true"));
        Assert.assertFalse(projected.contains("PREFIX_SHOULD_NOT_BE_PROJECTED"));
        Assert.assertFalse(projected.contains("x".repeat(3_000)));
    }

    @Test
    public void sessionSearchDiscoverProjectsMultiWindowAndLabelEvidence() {
        Map<String, Object> firstLabel = new LinkedHashMap<>();
        firstLabel.put("label", "28");
        firstLabel.put("score", 8.75);
        firstLabel.put("sample", "My disposable virtual card isn't working.");
        Map<String, Object> secondLabel = new LinkedHashMap<>();
        secondLabel.put("label", "67");
        secondLabel.put("score", 7.25);
        secondLabel.put("sample", "What are the restrictions on disposable cards?");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", "s1");
        result.put("match_message_id", 42);
        result.put("matched_role", "USER");
        result.put("content", "x".repeat(2_000));
        result.put("evidence_windows", List.of(
                "first window: disposable card is not working",
                "second window: disposable card restrictions"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("mode", "discover");
        data.put("count", 1);
        data.put("results", List.of(result));
        data.put("label_evidence", List.of(firstLabel, secondLabel));

        String projected = ToolObservationFormatter.format("session_search", data);

        Assert.assertTrue(projected.contains("evidence_window[1]:"));
        Assert.assertTrue(projected.contains("evidence_window[2]:"));
        Assert.assertTrue(projected.contains("label_evidence=ranked matches"));
        Assert.assertTrue(projected.contains("label=28"));
        Assert.assertTrue(projected.contains("sample=My disposable virtual card isn't working."));
        Assert.assertTrue(projected.contains("content_omitted_large=true"));
    }

    @Test
    public void webFetchKeepsContentAndDropsRequestEcho() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("url", "https://a.example");
        data.put("status", 200);
        data.put("prompt", "抽取关键结论");
        data.put("bytes", 4721);
        data.put("durationMs", 15576);
        data.put("contentFormat", "markdown");
        data.put("wordCount", 642);
        data.put("content", "页面正文");

        String projected = ToolObservationFormatter.format("WebFetch", data);

        Assert.assertEquals("页面正文", projected);
        Assert.assertFalse(projected.contains("抽取关键结论"));
        Assert.assertFalse(projected.contains("wordCount"));
    }

    @Test
    public void webFetchReportsNonSuccessStatusOnly() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("status", 404);
        data.put("content", "not found body");

        String projected = ToolObservationFormatter.format("WebFetch", data);

        Assert.assertTrue(projected.contains("status: 404"));
    }

    @Test
    public void deepSearchAcceptsPojoPayload() {
        // deep_search 传的是 POJO 而非 Map：入口必须归一化，否则投影整个失效。
        Map<String, Object> chapter = new LinkedHashMap<>();
        chapter.put("title", "第一章");
        chapter.put("summary", "章节结论");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", "Doc A");
        doc.put("url", "https://doc.example");
        Map<String, Object> queryResult = new LinkedHashMap<>();
        queryResult.put("query", "子查询");
        queryResult.put("docs", List.of(doc));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "deep_search");
        data.put("query", "原始查询回显");
        data.put("answerSummary", "总结正文");
        data.put("chapters", List.of(chapter));
        data.put("results", List.of(queryResult));

        String projected = ToolObservationFormatter.format("deep_search", data);

        Assert.assertTrue(projected.startsWith("总结正文"));
        Assert.assertTrue(projected.contains("第一章"));
        Assert.assertTrue(projected.contains("https://doc.example"));
        Assert.assertFalse(projected.contains("原始查询回显"));
    }

    @Test
    public void deepSearchReadsLinkAndLegacyUrl() {
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("title", "Linked doc");
        linked.put("link", "https://linked.example");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("title", "Legacy doc");
        legacy.put("url", "https://legacy.example");
        Map<String, Object> queryResult = new LinkedHashMap<>();
        queryResult.put("query", "query");
        queryResult.put("docs", List.of(linked, legacy));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerSummary", "answer");
        data.put("results", List.of(queryResult));

        String projected = ToolObservationFormatter.format("deep_search", data);

        Assert.assertTrue(projected.contains("https://linked.example"));
        Assert.assertTrue(projected.contains("https://legacy.example"));
    }

    // ---- 新增覆盖：子 Agent ----

    @Test
    public void agentKeepsAgentIdWithActionableHint() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("status", "completed");
        data.put("agentId", "b968e75b");
        data.put("content", "done");
        data.put("agentType", "code-executor");
        data.put("totalToolUseCount", 3);
        data.put("childSessionId", "sub_b968e75b");

        String projected = ToolObservationFormatter.format("Agent", data);

        Assert.assertTrue(projected.startsWith("done"));
        // agentId 是续跑入口，必须带可执行的 SendMessage 提示。
        Assert.assertTrue(projected.contains("agentId: b968e75b"));
        Assert.assertTrue(projected.contains("SendMessage with to: 'b968e75b'"));
        Assert.assertFalse(projected.contains("sub_b968e75b"));
        Assert.assertFalse(projected.contains("tool_uses"));
        Assert.assertFalse(projected.contains("status: completed"));
    }

    @Test
    public void agentWithoutContentSaysSo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("status", "completed");
        data.put("agentId", "a1");
        data.put("content", "");

        Assert.assertTrue(ToolObservationFormatter.format("Agent", data)
                .contains("Subagent completed but returned no output"));
    }

    @Test
    public void agentBackgroundKeepsOneAgentIdAndBackgroundMarker() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("status", "running");
        data.put("agentId", "bg-1");
        data.put("content", "");
        data.put("run_in_background", Boolean.TRUE);

        String projected = ToolObservationFormatter.format("Agent", data);

        Assert.assertEquals(1, countOccurrences(projected, "agentId: bg-1"));
        Assert.assertTrue(projected.contains("run_in_background: true"));
        Assert.assertTrue(projected.contains("status: running"));
    }

    @Test
    public void agentFailureKeepsActionableHint() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("errorMsg", "resume_agent_id removed");
        data.put("hint", "SendMessage(to=\"a1\", message=\"continue\")");

        String projected = ToolObservationFormatter.format("Agent", data, true);

        Assert.assertTrue(projected.contains("Error: resume_agent_id removed"));
        Assert.assertTrue(projected.contains("hint: SendMessage"));
    }

    @Test
    public void memoryBatchUsesCompactRowsAndOnlyShowsCapacityWhenNeeded() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("success", Boolean.TRUE);
        first.put("message", "saved");
        first.put("used_chars", 500);
        first.put("limit_chars", 10_000);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("success", Boolean.FALSE);
        second.put("message", "limit reached");
        second.put("used_chars", 9_500);
        second.put("limit_chars", 10_000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("results", List.of(first, second));

        String projected = ToolObservationFormatter.format("memory", data, true);

        Assert.assertTrue(projected.contains("- op 1: success; saved"));
        Assert.assertTrue(projected.contains("- op 2: failed; limit reached"));
        Assert.assertFalse(projected.contains("\"used_chars\""));
        Assert.assertFalse(projected.contains("usage: 500/10000"));
        Assert.assertTrue(projected.contains("usage: 9500/10000"));
    }

    @Test
    public void codeModeKeepsResultAndOnlyDiagnosticLogs() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("duration_ms", 1500);
        data.put("tool_calls", 9);
        data.put("source_hash", "abc123");
        data.put("result", "rows: 12");
        data.put("logs", List.of("[info] started", "[warn] slow request", "[error] retry used"));

        String projected = ToolObservationFormatter.format("code_mode", data);

        Assert.assertTrue(projected.contains("rows: 12"));
        Assert.assertTrue(projected.contains("[warn] slow request"));
        Assert.assertTrue(projected.contains("[error] retry used"));
        Assert.assertFalse(projected.contains("[info] started"));
        Assert.assertFalse(projected.contains("duration_ms"));
        Assert.assertFalse(projected.contains("tool_calls"));
        Assert.assertFalse(projected.contains("source_hash"));
    }

    @Test
    public void codeModeFailureKeepsErrorCodeMessageAndFiles() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("relative_path", "outputs/partial.csv");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("error_code", "CODE_MODE_TIMEOUT");
        data.put("message", "script timed out");
        data.put("logs", List.of("[info] started", "[error] timeout at step 2"));
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("code_mode", data, true);

        Assert.assertTrue(projected.contains("Error: CODE_MODE_TIMEOUT: script timed out"));
        Assert.assertTrue(projected.contains("[error] timeout at step 2"));
        Assert.assertTrue(projected.contains("outputs/partial.csv"));
        Assert.assertFalse(projected.contains("\"error_code\""));
    }

    @Test
    public void docGenerateDropsInternalPayloadAndDoesNotRepeatFiles() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "report.docx");
        file.put("file_size", 4096);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("message", "生成完成");
        data.put("warnings", List.of("missing chart alt text"));
        data.put("lint_warnings", List.of("font fallback"));
        data.put("payload", "internal request payload");
        data.put("resolved", "resolved config");
        data.put("colors", List.of("#fff"));
        data.put("usage", Map.of("tokens", 10));
        data.put("stats", Map.of("duration", 3));
        data.put("rendered", "internal render");
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("document_generate", data);

        Assert.assertTrue(projected.contains("missing chart alt text"));
        Assert.assertTrue(projected.contains("font fallback"));
        Assert.assertFalse(projected.contains("生成完成"));
        Assert.assertFalse(projected.contains("report.docx"));
        Assert.assertFalse(projected.contains("internal request payload"));
        Assert.assertFalse(projected.contains("resolved config"));
        Assert.assertFalse(projected.contains("internal render"));
    }

    @Test
    public void docGenerateFailureKeepsWarningsAndPartialFilesWithoutEnvelope() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "partial.docx");
        file.put("file_size", 4096);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("message", "lint failed");
        data.put("lint_warnings", List.of("missing title"));
        data.put("payload", "internal payload");
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("document_generate", data, true);

        Assert.assertTrue(projected.contains("Error: lint failed"));
        Assert.assertTrue(projected.contains("missing title"));
        Assert.assertTrue(projected.contains("partial.docx"));
        Assert.assertFalse(projected.contains("internal payload"));
        Assert.assertFalse(projected.contains("file_size"));
    }

    @Test
    public void docTemplateKeepsRenderedAsPrimaryOutputButDropsInternalConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("message", "template ready");
        data.put("name", "quarterly");
        data.put("kind", "docx");
        data.put("path", "templates/quarterly.docx");
        data.put("rendered", "Rendered template body");
        data.put("variables", List.of("title", "owner"));
        data.put("resolved", "resolved config");
        data.put("usage", Map.of("tokens", 42));

        String projected = ToolObservationFormatter.format("document_template", data);

        Assert.assertTrue(projected.contains("template ready"));
        Assert.assertTrue(projected.contains("name: quarterly"));
        Assert.assertTrue(projected.contains("Rendered template body"));
        Assert.assertFalse(projected.contains("variables"));
        Assert.assertFalse(projected.contains("resolved config"));
        Assert.assertFalse(projected.contains("tokens"));
    }

    @Test
    public void docReadKeepsBodyAndPathsButDropsEnvelopeMetadata() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "clean.csv");
        file.put("file_size", 99);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "data_clean");
        data.put("ok", Boolean.TRUE);
        data.put("message", "处理完成");
        data.put("data", "id,name\n1,Ada");
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("data_clean", data);

        Assert.assertTrue(projected.startsWith("id,name\n1,Ada"));
        Assert.assertTrue(projected.contains("clean.csv"));
        Assert.assertFalse(projected.contains("处理完成"));
        Assert.assertFalse(projected.contains("file_size"));
        Assert.assertFalse(projected.contains("\"tool\""));
    }

    @Test
    public void docReadKeepsStructuredData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("data", Map.of("rows", List.of(Map.of("a", 1)), "columns", List.of("a")));

        String projected = ToolObservationFormatter.format("excel_reader", data);

        Assert.assertTrue(projected.contains("\"columns\":[\"a\"]"));
        Assert.assertFalse(projected.contains("\"ok\""));
    }

    @Test
    public void dataAnalysisDropsTaskEchoAndKeepsContentAndCanonicalFile() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "analysis.xlsx");
        file.put("url", "https://files.example/analysis.xlsx");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "data_analysis");
        data.put("ok", Boolean.TRUE);
        data.put("task", "分析销售趋势");
        data.put("content", "收入持续增长。");
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("data_analysis", data);

        Assert.assertTrue(projected.contains("收入持续增长。"));
        Assert.assertTrue(projected.contains("analysis.xlsx"));
        Assert.assertTrue(projected.contains("https://files.example/analysis.xlsx"));
        Assert.assertFalse(projected.contains("分析销售趋势"));
    }

    @Test
    public void imageGenerationDropsPromptAndParametersButKeepsCanonicalUrl() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "poster.png");
        file.put("mimeType", "image/png");
        file.put("url", "https://files.example/poster.png");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("summary", "已生成海报");
        data.put("prompt", "生成活动海报");
        data.put("mode", "text-to-image");
        data.put("size", "1536x1024");
        data.put("batchCount", 2);
        data.put("hint", "Use produced_files.url as image.url");
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("image_generation_tool", data);

        Assert.assertTrue(projected.contains("已生成海报"));
        Assert.assertTrue(projected.contains("https://files.example/poster.png"));
        Assert.assertFalse(projected.contains("生成活动海报"));
        Assert.assertFalse(projected.contains("text-to-image"));
        Assert.assertFalse(projected.contains("1536x1024"));
        Assert.assertFalse(projected.contains("batchCount"));
        Assert.assertFalse(projected.contains("image/png"));
        Assert.assertFalse(projected.contains("Use produced_files.url"));
    }

    @Test
    public void mcpResourceListUsesOneCompactRowPerResource() {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", "docs://guide");
        resource.put("name", "Guide");
        resource.put("title", "Guide");
        resource.put("description", "Usage guide");
        resource.put("mimeType", "text/markdown");
        resource.put("size", 1024);
        resource.put("server", "docs");
        resource.put("mcpId", "mcp-1");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("resources", List.of(resource));
        data.put("count", 1);
        data.put("total", 3);
        data.put("truncated", Boolean.TRUE);

        String projected = ToolObservationFormatter.format("ListMcpResources", data);

        Assert.assertTrue(projected.contains("[docs] Guide — docs://guide"));
        Assert.assertTrue(projected.contains("description: Usage guide"));
        Assert.assertTrue(projected.contains("showing 1 of 3"));
        Assert.assertFalse(projected.contains("text/markdown"));
        Assert.assertFalse(projected.contains("mcp-1"));
        Assert.assertFalse(projected.contains("1024"));
    }

    @Test
    public void mcpResourceReadKeepsContentAndTruncationButDropsEcho() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("server", "docs");
        data.put("uri", "docs://guide");
        data.put("content", "Guide body");
        data.put("truncated", Boolean.TRUE);
        data.put("originalChars", 50_000);

        String projected = ToolObservationFormatter.format("ReadMcpResource", data);

        Assert.assertTrue(projected.startsWith("Guide body"));
        Assert.assertTrue(projected.contains("content truncated at 40000 chars"));
        Assert.assertFalse(projected.contains("docs://guide"));
        Assert.assertFalse(projected.contains("originalChars"));
    }

    // ---- 新增覆盖：Plan Mode ----

    @Test
    public void enterPlanModeDropsDuplicateInstructions() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("entered", Boolean.TRUE);
        data.put("message", "Entered plan mode.");
        data.put("planFilePath", ".cute/plan.md");
        data.put("instructions", "PLAN_MODE_INSTRUCTIONS_V2".repeat(100));

        String projected = ToolObservationFormatter.format("EnterPlanMode", data);

        // instructions 已注入 system prompt，工具结果再带一份就是双份全文。
        Assert.assertFalse(projected.contains("PLAN_MODE_INSTRUCTIONS_V2"));
        Assert.assertTrue(projected.contains(".cute/plan.md"));
    }

    @Test
    public void exitPlanModeDropsDuplicatePlanBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("approved", Boolean.TRUE);
        data.put("message", "User has approved your plan.\n\n## Approved Plan:\n\n步骤一");
        data.put("plan", "步骤一");

        String projected = ToolObservationFormatter.format("ExitPlanMode", data);

        Assert.assertEquals(1, countOccurrences(projected, "步骤一"));
    }

    // ---- 新增覆盖：bash / code_execution ----

    @Test
    public void bashDropsSkillInventory() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("stdout", "ok\n");
        data.put("stderr", "");
        data.put("exit_code", 0);
        data.put("cwd", ".");
        data.put("duration_ms", 9462);
        data.put("skills_materialized", List.of("a", "b", "c"));
        data.put("skills_synced_back", List.of());

        String projected = ToolObservationFormatter.format("bash", data);

        Assert.assertEquals("ok", projected);
        Assert.assertFalse(projected.contains("skills_materialized"));
        Assert.assertFalse(projected.contains("duration_ms"));
        Assert.assertFalse(projected.contains("cwd"));
    }

    @Test
    public void codeExecutionSurfacesErrorAndFiles() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "out.csv");
        file.put("url", "https://files.example/out.csv");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("status", "ok");
        data.put("stdout", "rows: 10");
        data.put("stderr", "");
        data.put("hint", "Use produced_files.url as image.url");
        data.put("produced_files", List.of(file));

        String projected = ToolObservationFormatter.format("code_execution", data);

        Assert.assertTrue(projected.contains("rows: 10"));
        Assert.assertTrue(projected.contains("out.csv"));
        Assert.assertFalse(projected.contains("produced_files.url as image.url"));
    }

    @Test
    public void stateAwareFailureProjectionDropsEnvelopeMetadata() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("tool", "host_cli");
        data.put("stdout", "partial");
        data.put("stderr", "boom");
        data.put("exit_code", 2);
        data.put("duration_ms", 1234);
        data.put("error_hint", "Fix the input and retry.");
        data.put("retry", "safe");

        String projected = ToolObservationFormatter.format("host_cli", data, true);

        Assert.assertTrue(projected.contains("partial"));
        Assert.assertTrue(projected.contains("stderr:\nboom"));
        Assert.assertTrue(projected.contains("exit_code: 2"));
        Assert.assertTrue(projected.contains("hint: Fix the input and retry."));
        Assert.assertTrue(projected.contains("retry: safe"));
        Assert.assertFalse(projected.contains("duration_ms"));
        Assert.assertFalse(projected.contains("\"tool\""));
    }

    @Test
    public void stateAwareSimpleFailureDetailBecomesOneLineError() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "tool_error");
        detail.put("message", "缺少用户身份，无法执行命令");

        Assert.assertEquals("Error: 缺少用户身份，无法执行命令",
                ToolObservationFormatter.format("bash", detail, true));
        Assert.assertNull("白名单外的工具仍保留原 JSON 回退契约",
                ToolObservationFormatter.format("workspace_write", detail, true));
    }

    @Test
    public void browserProjectionDropsImageBase64ButKeepsTreeAndUrl() {
        String base64 = "iVBORw0KGgo" + "A".repeat(20_000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("stdout", "{\"url\":\"https://example.com\","
                + "\"tree\":\"page tree\",\"imageBase64\":\"" + base64 + "\"}");
        data.put("stderr", "");
        data.put("exit_code", 0);

        String projected = ToolObservationFormatter.format("browser", data);

        Assert.assertTrue(projected.contains("https://example.com"));
        Assert.assertTrue(projected.contains("page tree"));
        Assert.assertFalse(projected.contains(base64));
        Assert.assertTrue(projected.contains("\"imageBase64\":\"[omitted]\""));
    }

    @Test
    public void browserProjectionKeepsFailedArtifactReasonAndUploadFailure() {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("relativePath", "downloads/partial.pdf");
        failed.put("reason", "artifact_upload_failed");
        failed.put("message", "upload timed out");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("stdout", "");
        data.put("stderr", "capture failed");
        data.put("exit_code", 1);
        data.put("artifact_failures", List.of(failed));
        data.put("image_upload_failed", Boolean.TRUE);
        data.put("message", "Viewport screenshot upload failed.");

        String projected = ToolObservationFormatter.format("browser", data, true);

        Assert.assertTrue(projected.contains("downloads/partial.pdf"));
        Assert.assertTrue(projected.contains("artifact_upload_failed"));
        Assert.assertTrue(projected.contains("upload timed out"));
        Assert.assertTrue(projected.contains("[image upload failed]"));
    }

    @Test
    public void codeExecutionDropsDuplicateResultAndSyncSuccessButKeepsIssues() {
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("status", "conflict");
        sync.put("errorCode", "WORKSPACE_WRITE_CONFLICT");
        sync.put("conflicts", List.of(Map.of(
                "code", "WORKSPACE_WRITE_CONFLICT",
                "path", "output/report.csv",
                "message", "both sides changed")));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_name", "chart.png");
        file.put("relative_path", "output/chart.png");
        file.put("url", "https://files.example/preview/chart.png");
        file.put("file_size", 42);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("status", "error");
        data.put("stdout", "same result");
        data.put("stderr", "");
        data.put("result", "same result");
        data.put("error", "execution failed");
        data.put("produced_files", List.of(file));
        data.put("deleted_files", List.of(Map.of("relativePath", "tmp/stale.csv")));
        data.put("workspace_sync", sync);

        String projected = ToolObservationFormatter.format("code_execution", data, true);

        Assert.assertEquals(1, countOccurrences(projected, "same result"));
        Assert.assertFalse(projected.contains("result: same result"));
        Assert.assertTrue(projected.contains("output/chart.png"));
        Assert.assertTrue(projected.contains("https://files.example/preview/chart.png"));
        Assert.assertFalse(projected.contains("file_size"));
        Assert.assertTrue(projected.contains("deleted:\n- tmp/stale.csv"));
        Assert.assertTrue(projected.contains("workspace_sync: conflict"));
        Assert.assertTrue(projected.contains("WORKSPACE_WRITE_CONFLICT"));
    }

    // ---- 新增覆盖：任务 / 画布 / 检索元工具 ----

    @Test
    public void taskListRendersCompactRows() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", "1");
        task.put("status", "pending");
        task.put("subject", "写计划");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("tasks", List.of(task));

        Assert.assertEquals("- [1] pending 写计划", ToolObservationFormatter.format("TaskList", data));
    }

    @Test
    public void toolSearchKeepsMatchesAndHint() {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("name", "mcp__maps__text_search");
        match.put("description", "按关键词搜索地点");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.TRUE);
        data.put("matches", List.of(match));
        data.put("hint", "Retry with the service name.");

        String projected = ToolObservationFormatter.format("ToolSearch", data);

        Assert.assertTrue(projected.contains("mcp__maps__text_search"));
        Assert.assertTrue(projected.contains("Retry with the service name."));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
