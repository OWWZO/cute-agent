package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;

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
                .requestId("req-10")
                .sessionId("sess-10")
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
        ExecutionRunDetail detail = ExecutionRunDetail.builder()
                .run(run)
                .artifacts(List.of(artifact))
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
        Assert.assertTrue(trace.getJSONObject("run").containsKey("finalSummaryText"));
        Assert.assertNull(trace.getJSONObject("run").get("finalSummaryText"));
        Assert.assertTrue(trace.getJSONObject("run").getString("queryText")
                .contains("[REDACTED_EMAIL]"));
        JSONObject projected = trace.getJSONArray("artifacts").getJSONObject(0);
        Assert.assertNull(projected.get("downloadUrl"));
        Assert.assertNull(projected.get("storageKey"));
        Assert.assertEquals("artifacts/artifact-7.xlsx", projected.getString("relativeRef"));
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
