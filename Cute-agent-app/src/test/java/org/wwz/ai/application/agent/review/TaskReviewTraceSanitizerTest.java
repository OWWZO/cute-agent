package org.wwz.ai.application.agent.review;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class TaskReviewTraceSanitizerTest {

    @Test
    public void sanitizeRedactsSecretsAndProjectsArtifacts() {
        JSONObject trace = new JSONObject();
        JSONObject run = new JSONObject();
        run.put("requestId", "req-1");
        trace.put("run", run);

        JSONArray conversation = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("content", "Authorization: Bearer sk-secret-value");
        conversation.add(message);
        trace.put("conversation", conversation);

        JSONArray artifacts = new JSONArray();
        JSONObject artifact = new JSONObject();
        artifact.put("id", "artifact-1");
        artifact.put("fileName", "secret.txt");
        artifact.put("downloadUrl", "http://localhost/file");
        artifact.put("mimeType", "text/plain");
        artifacts.add(artifact);
        trace.put("artifacts", artifacts);

        JSONObject sanitized = TaskReviewTraceSanitizer.sanitize(trace);

        String text = sanitized.toJSONString();
        Assert.assertFalse(text.contains("sk-secret-value"));
        Assert.assertFalse(text.contains("secret.txt"));
        Assert.assertFalse(text.contains("localhost"));
        Assert.assertEquals(
                "artifacts/artifact-artifact-1.txt",
                sanitized.getJSONArray("artifacts")
                        .getJSONObject(0)
                        .getString("relativeRef")
        );
    }
}
