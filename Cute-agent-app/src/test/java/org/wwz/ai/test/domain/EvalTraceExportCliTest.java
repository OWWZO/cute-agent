package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ContextEventView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.MemoryEventView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

public class EvalTraceExportCliTest {

    @Test
    public void shouldWriteSanitizedTraceWithoutArtifactLinks() throws Exception {
        Path out = Files.createTempDirectory("cute-trace-export");
        DialogueRunView run = DialogueRunView.builder()
                .id(10L)
                .runUid("run-10")
                .requestId("req-10")
                .sessionId("sess-10")
                .userId("user-123")
                .entryAgent("react")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .queryText("联系 alice@example.com")
                .finalSummaryText(null)
                .startedAt(LocalDateTime.of(2026, 11, 6, 10, 0))
                .finishedAt(LocalDateTime.of(2026, 11, 6, 10, 0, 1))
                .durationMs(1000L)
                .totalTokensTotal(100)
                .build();
        ArtifactView artifact = ArtifactView.builder()
                .id(7L)
                .artifactRole("output")
                .sourceType("tool_output")
                .fileName("private.xlsx")
                .storageKey("bucket/private.xlsx")
                .downloadUrl("https://internal.example.com/download?token=abc")
                .mimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .fileSize(2048L)
                .fileHash("sha256:abc")
                .build();
        ContextEventView compaction = ContextEventView.builder()
                .eventId("compaction:7")
                .type("compaction")
                .status("success")
                .strategy("full-llm")
                .beforeTokens(1200)
                .afterTokens(300)
                .summaryHash(
                        "sha256:fcdec6df4d44dbc637c7c5b58efface"
                                + "52a7f8a88535423430255be0bb89bedd8"
                )
                .build();
        MemoryEventView memoryFork = MemoryEventView.builder()
                .eventId("memory-fork:7")
                .type("memory_fork")
                .status("success")
                .ownerType("USER")
                .ownerHash(
                        "sha256:fcdec6df4d44dbc637c7c5b58efface"
                                + "52a7f8a88535423430255be0bb89bedd8"
                )
                .appliedCount(2)
                .build();
        ExecutionRunDetail detail = ExecutionRunDetail.builder()
                .run(run)
                .artifacts(List.of(artifact))
                .contextEvents(List.of(compaction))
                .memoryEvents(List.of(memoryFork))
                .build();
        DialogueRunView running = DialogueRunView.builder()
                .id(11L)
                .requestId("req-11")
                .sessionId("sess-11")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .queryText("still running")
                .finalSummaryText("")
                .build();

        List<Path> paths = EvalTraceExportCli.writeTraces(
                List.of(detail, ExecutionRunDetail.builder().run(running).build()),
                out,
                new JSONObject()
        );

        Assert.assertEquals(1, paths.size());
        String text = Files.readString(paths.get(0), StandardCharsets.UTF_8);
        JSONObject trace = JSON.parseObject(text);
        Assert.assertEquals("SUCCESS", trace.getJSONObject("run").getString("status"));
        Assert.assertEquals("run-10", trace.getJSONObject("run").getString("runUid"));
        Assert.assertEquals(
                "sha256:fcdec6df4d44dbc637c7c5b58efface52a7f8a88535423430255be0bb89bedd8",
                trace.getJSONObject("run").getString("userIdHash"));
        Assert.assertFalse(trace.getJSONObject("run").containsKey("userId"));
        Assert.assertTrue(trace.getJSONObject("run").containsKey("finalSummaryText"));
        Assert.assertNull(trace.getJSONObject("run").get("finalSummaryText"));
        Assert.assertTrue(trace.getJSONObject("run").getString("queryText")
                .contains("[REDACTED_EMAIL]"));
        JSONObject projected = trace.getJSONArray("artifacts").getJSONObject(0);
        Assert.assertNull(projected.get("downloadUrl"));
        Assert.assertNull(projected.get("storageKey"));
        Assert.assertEquals("artifacts/artifact-7.xlsx", projected.getString("relativeRef"));
        JSONObject contextEvent = trace.getJSONArray("contextEvents").getJSONObject(0);
        Assert.assertEquals("compaction:7", contextEvent.getString("eventId"));
        Assert.assertFalse(contextEvent.containsKey("summaryText"));
        Assert.assertFalse(contextEvent.containsKey("errorMessage"));
        JSONObject memoryEvent = trace.getJSONArray("memoryEvents").getJSONObject(0);
        Assert.assertEquals("memory-fork:7", memoryEvent.getString("eventId"));
        Assert.assertFalse(memoryEvent.containsKey("ownerId"));
        Assert.assertFalse(memoryEvent.containsKey("detailJson"));
        Assert.assertTrue(EvalTraceSanitizer.scanSensitive(text).isEmpty());
    }

    @Test
    public void shouldFilterTraceStatusesWhenWritingExport() throws Exception {
        Path out = Files.createTempDirectory("cute-trace-export-status");
        DialogueRunView successful = DialogueRunView.builder()
                .id(20L)
                .requestId("req-success")
                .sessionId("sess-20")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .queryText("success")
                .finalSummaryText("ok")
                .build();
        DialogueRunView failed = DialogueRunView.builder()
                .id(21L)
                .requestId("req-failed")
                .sessionId("sess-21")
                .status(ExecutionLedgerConstants.STATUS_FAILED)
                .queryText("failed")
                .finalSummaryText("error")
                .build();

        List<Path> paths = EvalTraceExportCli.writeTraces(
                List.of(
                        ExecutionRunDetail.builder().run(successful).build(),
                        ExecutionRunDetail.builder().run(failed).build()
                ),
                out,
                new JSONObject(),
                List.of(ExecutionLedgerConstants.STATUS_FAILED)
        );

        Assert.assertEquals(1, paths.size());
        Assert.assertEquals("req-failed", JSON.parseObject(
                Files.readString(paths.get(0), StandardCharsets.UTF_8)
        ).getJSONObject("run").getString("requestId"));
    }
}
