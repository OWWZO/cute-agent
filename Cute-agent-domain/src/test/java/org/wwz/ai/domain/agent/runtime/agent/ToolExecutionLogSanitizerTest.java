package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;

/**
 * 工具执行日志脱敏：DEBUG 打印的工具入参不得带出常见凭证字段，超大载荷必须截断。
 */
public class ToolExecutionLogSanitizerTest {

    @Test
    public void jsonCredentialFieldsAreMasked() {
        String sanitized = ToolExecutionPipeline.abbreviate(
                "{\"apiKey\":\"sk-live-123\",\"headers\":{\"Authorization\":\"Bearer abc\"}}");

        Assert.assertFalse(sanitized.contains("sk-live-123"));
        Assert.assertFalse("Bearer 后的 token 也必须脱敏", sanitized.contains("abc"));
        Assert.assertTrue(sanitized.contains("***"));
    }

    @Test
    public void mapStyleCredentialFieldsAreMasked() {
        String sanitized = ToolExecutionPipeline.abbreviate(
                "{token=abc123, secret=s3cr3t, url=https://example.com}");

        Assert.assertFalse(sanitized.contains("abc123"));
        Assert.assertFalse(sanitized.contains("s3cr3t"));
    }

    @Test
    public void unrelatedFieldsAreNotMasked() {
        String sanitized = ToolExecutionPipeline.abbreviate("{\"maxTokens\":1024,\"query\":\"hello\"}");

        Assert.assertTrue(sanitized.contains("1024"));
        Assert.assertTrue(sanitized.contains("hello"));
    }

    @Test
    public void oversizedPayloadIsTruncated() {
        String sanitized = ToolExecutionPipeline.abbreviate("x".repeat(5000));

        Assert.assertTrue(sanitized.endsWith("(truncated, total=5000)"));
        Assert.assertTrue(sanitized.length() < 5000);
    }
}
