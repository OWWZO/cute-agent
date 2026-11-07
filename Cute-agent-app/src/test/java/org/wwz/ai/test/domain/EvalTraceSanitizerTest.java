package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class EvalTraceSanitizerTest {

    @Test
    public void shouldRedactSecretsPiiPathsAndArtifactLinks() {
        JSONObject trace = new JSONObject();
        JSONObject run = new JSONObject();
        run.put("requestId", "req-1");
        run.put("sessionId", "sess-1");
        run.put("status", "FAILED");
        run.put("queryText",
                "联系 13800138000，邮箱 alice@example.com，地址 北京市朝阳区建国路88号");
        run.put("finalSummaryText",
                "Authorization: Bearer abcdefghijklmnopqrstuvwxyz");
        run.put("errorMsg",
                "password=secret-value path=C:\\Users\\wwz\\private.txt");
        trace.put("run", run);

        JSONObject llm = new JSONObject();
        llm.put("responseText",
                "api_key=sk-test-placeholder-1234567890");
        JSONArray llms = new JSONArray();
        llms.add(llm);
        trace.put("llmInvocations", llms);

        JSONObject tool = new JSONObject();
        tool.put("toolName", "read_file");
        tool.put("inputJson",
                "{\"path\":\"C:\\\\Users\\\\wwz\\\\private.txt\","
                        + "\"url\":\"http://127.0.0.1:3425/v1?token=abc\"}");
        tool.put("status", "FAILED");
        tool.put("errorMsg", "cookie=session-cookie; 身份证 11010519491231002X");
        JSONArray tools = new JSONArray();
        tools.add(tool);
        trace.put("toolInvocations", tools);

        JSONObject artifact = new JSONObject();
        artifact.put("id", 7);
        artifact.put("artifactRole", "output");
        artifact.put("sourceType", "tool_output");
        artifact.put("fileName", "alice-private.xlsx");
        artifact.put("storageKey", "bucket/internal/alice-private.xlsx");
        artifact.put("downloadUrl", "https://internal.example.com/download?token=abc");
        artifact.put("previewUrl", "https://internal.example.com/preview");
        artifact.put("mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        artifact.put("fileSize", 2048);
        artifact.put("fileHash", "sha256:abc");
        artifact.put("metadataJson", "{\"owner\":\"alice@example.com\"}");
        JSONArray artifacts = new JSONArray();
        artifacts.add(artifact);
        trace.put("artifacts", artifacts);
        trace.put("environment", new JSONObject());

        JSONObject sanitized = EvalTraceSanitizer.sanitize(trace);
        String text = sanitized.toJSONString();

        Assert.assertFalse(text.contains("13800138000"));
        Assert.assertFalse(text.contains("alice@example.com"));
        Assert.assertFalse(text.contains("11010519491231002X"));
        Assert.assertFalse(text.contains("sk-test-placeholder-1234567890"));
        Assert.assertFalse(text.contains("C:\\\\Users\\\\wwz"));
        Assert.assertFalse(text.contains("127.0.0.1"));
        Assert.assertFalse(text.contains("private.xlsx"));
        Assert.assertTrue(text.contains("[REDACTED_PHONE]"));

        JSONObject projected = sanitized.getJSONArray("artifacts").getJSONObject(0);
        Assert.assertNull(projected.get("fileName"));
        Assert.assertNull(projected.get("storageKey"));
        Assert.assertNull(projected.get("downloadUrl"));
        Assert.assertNull(projected.get("previewUrl"));
        Assert.assertEquals("artifacts/artifact-7.xlsx", projected.getString("relativeRef"));
        Assert.assertEquals("sha256:abc", projected.getString("fileHash"));
        Assert.assertTrue(EvalTraceSanitizer.scanSensitive(text).isEmpty());
    }
}
