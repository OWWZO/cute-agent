package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class EvalFixtureRunnerCliTest {

    @Test
    public void shouldExportTraceWithToolCallAndFirstTokenTiming() throws Exception {
        Path temp = Files.createTempDirectory("cute-eval-cli-test");
        Path cases = temp.resolve("cases.json");
        Path out = temp.resolve("out");
        Files.writeString(cases, """
                {
                  "schemaVersion": "1.0",
                  "environment": {
                    "profile": "pr",
                    "mode": "fixture",
                    "modelId": "eval-fixture-model"
                  },
                  "cases": [{
                    "id": "react.test.tool",
                    "query": "read config",
                    "tools": [{
                      "name": "read_file",
                      "description": "official benchmark tool schema",
                      "parameters": {
                        "type": "dict",
                        "properties": {
                          "path": {"type": "string"}
                        },
                        "required": ["path"]
                      }
                    }],
                    "script": [
                      {
                        "type": "tool",
                        "tool": "read_file",
                        "arguments": {"path": "config.yaml"},
                        "result": "db.port=3306",
                        "thought": "read config",
                        "totalTokens": 100
                      },
                      {
                        "type": "final",
                        "content": "db.port is 3306",
                        "totalTokens": 80
                      }
                    ]
                  }]
                }
                """, StandardCharsets.UTF_8);

        EvalFixtureRunnerCli.main(new String[]{
                "--cases", cases.toString(),
                "--out", out.toString()
        });

        JSONObject trace = JSON.parseObject(Files.readString(
                out.resolve("traces/react.test.tool/trace.json"),
                StandardCharsets.UTF_8));
        Assert.assertEquals("1.1", trace.getString("schemaVersion"));
        Assert.assertEquals(1, trace.getJSONArray("toolInvocations").size());
        Assert.assertEquals("read_file",
                trace.getJSONArray("toolInvocations").getJSONObject(0).getString("toolName"));
        Assert.assertEquals(
                "{\"path\":\"config.yaml\"}",
                trace.getJSONArray("toolInvocations").getJSONObject(0).getString("inputJson"));
        Assert.assertEquals(2, trace.getJSONArray("llmInvocations").size());
        Assert.assertNotNull(
                trace.getJSONArray("llmInvocations").getJSONObject(0).getString("firstTokenAt"));
        Assert.assertEquals("SUCCESS", trace.getJSONObject("run").getString("status"));
        Assert.assertEquals("pr", trace.getJSONObject("environment").getString("profile"));
        Assert.assertEquals("eval-fixture-model",
                trace.getJSONObject("environment").getString("modelId"));
    }

    @Test
    public void shouldExportLlmFactsWithoutCoercingNullToZero() {
        DialogueRunView run = DialogueRunView.builder()
                .requestId("req-llm-facts")
                .sessionId("sess-llm-facts")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .build();
        LlmInvocationView valued = LlmInvocationView.builder()
                .promptTokens(100)
                .completionTokens(20)
                .totalTokens(120)
                .cachedPromptTokens(0)
                .promptTextTokens(90)
                .promptAudioTokens(1)
                .promptImageTokens(9)
                .completionTextTokens(15)
                .completionAudioTokens(2)
                .reasoningTokens(3)
                .systemFingerprint("sha256:system")
                .estTotalTokens(110)
                .estSystemTokens(20)
                .estMessageTokens(70)
                .estToolTokens(20)
                .messageCount(4)
                .toolCount(2)
                .cacheStatus("OK")
                .cacheRiskFlags("none")
                .build();
        LlmInvocationView missing = LlmInvocationView.builder()
                .promptTokens(50)
                .build();

        JSONObject trace = EvalFixtureRunnerCli.toTrace(
                "llm.facts",
                ExecutionRunDetail.builder()
                        .run(run)
                        .llmInvocations(List.of(valued, missing))
                        .build());
        JSONObject actual = trace.getJSONArray("llmInvocations").getJSONObject(0);
        JSONObject absent = trace.getJSONArray("llmInvocations").getJSONObject(1);

        Assert.assertEquals(0, actual.getIntValue("cachedPromptTokens"));
        Assert.assertEquals(90, actual.getIntValue("promptTextTokens"));
        Assert.assertEquals(1, actual.getIntValue("promptAudioTokens"));
        Assert.assertEquals(9, actual.getIntValue("promptImageTokens"));
        Assert.assertEquals(15, actual.getIntValue("completionTextTokens"));
        Assert.assertEquals(2, actual.getIntValue("completionAudioTokens"));
        Assert.assertEquals(3, actual.getIntValue("reasoningTokens"));
        Assert.assertEquals("sha256:system", actual.getString("systemFingerprint"));
        Assert.assertEquals(110, actual.getIntValue("estTotalTokens"));
        Assert.assertEquals(20, actual.getIntValue("estSystemTokens"));
        Assert.assertEquals(70, actual.getIntValue("estMessageTokens"));
        Assert.assertEquals(20, actual.getIntValue("estToolTokens"));
        Assert.assertEquals(4, actual.getIntValue("messageCount"));
        Assert.assertEquals(2, actual.getIntValue("toolCount"));
        Assert.assertEquals("OK", actual.getString("cacheStatus"));
        Assert.assertEquals("none", actual.getString("cacheRiskFlags"));
        Assert.assertTrue(absent.containsKey("cachedPromptTokens"));
        Assert.assertNull(absent.get("cachedPromptTokens"));
    }

    @Test
    public void shouldExportRunAndSubAgentFactsWithoutPlainUserId() {
        DialogueRunView run = DialogueRunView.builder()
                .runUid("run-uid-001")
                .requestId("req-run-facts")
                .sessionId("sess-run-facts")
                .userId("user-123")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .build();
        ToolInvocationView attributed = ToolInvocationView.builder()
                .toolCallId("call-child")
                .parentToolCallId("call-parent")
                .subAgentId("agent-child")
                .subAgentType("research")
                .subAgentDescription("扫描项目结构")
                .toolName("workspace_read")
                .artifactCount(0)
                .build();
        ToolInvocationView missing = ToolInvocationView.builder()
                .toolCallId("call-main")
                .toolName("workspace_read")
                .build();

        JSONObject trace = EvalFixtureRunnerCli.toTrace(
                "run.facts",
                ExecutionRunDetail.builder()
                        .run(run)
                        .toolInvocations(List.of(attributed, missing))
                        .build());
        JSONObject actualRun = trace.getJSONObject("run");
        JSONObject actualTool = trace.getJSONArray("toolInvocations").getJSONObject(0);
        JSONObject absentTool = trace.getJSONArray("toolInvocations").getJSONObject(1);

        Assert.assertEquals("run-uid-001", actualRun.getString("runUid"));
        Assert.assertEquals(
                "sha256:fcdec6df4d44dbc637c7c5b58efface52a7f8a88535423430255be0bb89bedd8",
                actualRun.getString("userIdHash"));
        Assert.assertFalse(actualRun.containsKey("userId"));
        Assert.assertEquals("agent-child", actualTool.getString("subAgentId"));
        Assert.assertEquals("research", actualTool.getString("subAgentType"));
        Assert.assertEquals("扫描项目结构", actualTool.getString("subAgentDescription"));
        Assert.assertEquals(0, actualTool.getIntValue("artifactCount"));
        Assert.assertTrue(absentTool.containsKey("artifactCount"));
        Assert.assertNull(absentTool.get("artifactCount"));
        Assert.assertTrue(absentTool.containsKey("subAgentId"));
        Assert.assertNull(absentTool.get("subAgentId"));
    }
}
