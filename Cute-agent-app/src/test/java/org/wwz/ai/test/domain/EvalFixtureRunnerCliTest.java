package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        Assert.assertEquals("1.0", trace.getString("schemaVersion"));
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
}
