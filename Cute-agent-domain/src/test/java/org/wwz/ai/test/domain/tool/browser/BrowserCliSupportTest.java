package org.wwz.ai.test.domain.tool.browser;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.cli.CliResult;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.browser.BrowserCliSupport;
import org.wwz.ai.types.agent.config.OpenCliProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两个浏览器工具共用的 CLI 辅助纯函数。
 * <p>
 * AgentBrowserTool 与 BrowserTool 84% 逐字相同，其中 extractViewport /
 * resolveTimeout / resolveCacheDir / stringList 等纯函数已收敛到
 * {@link BrowserCliSupport}。这组测试锁住它们的解析规则，防止两个工具
 * 因各自的私有副本逐渐漂移。
 */
public class BrowserCliSupportTest {

    @Test
    public void extractsViewportFromStdoutJson() {
        Map<String, Object> payload = BrowserCliSupport.extractViewport(
                "noise before {\"url\":\"https://a.com\",\"tree\":{\"n\":1}} noise after");

        Assert.assertEquals("https://a.com", payload.get("url"));
        Assert.assertNotNull(payload.get("tree"));
    }

    @Test
    public void flattensNestedDataObjectWithoutOverwritingTopLevel() {
        // data 里的键只补充，不覆盖顶层同名键
        Map<String, Object> payload = BrowserCliSupport.extractViewport(
                "{\"url\":\"top\",\"data\":{\"url\":\"nested\",\"extra\":\"v\"}}");

        Assert.assertEquals("top", payload.get("url"));
        Assert.assertEquals("v", payload.get("extra"));
    }

    @Test
    public void returnsEmptyViewportForBlankOrMalformedStdout() {
        Assert.assertTrue(BrowserCliSupport.extractViewport(null).isEmpty());
        Assert.assertTrue(BrowserCliSupport.extractViewport("   ").isEmpty());
        Assert.assertTrue(BrowserCliSupport.extractViewport("no json here").isEmpty());
        // 只有左括号：lastIndexOf('}') <= start
        Assert.assertTrue(BrowserCliSupport.extractViewport("{unclosed").isEmpty());
    }

    @Test
    public void clampsRequestedTimeoutToConfiguredMax() {
        OpenCliProperties properties = new OpenCliProperties();
        properties.setDefaultTimeoutMs(180_000L);
        properties.setMaxTimeoutMs(300_000L);

        Assert.assertEquals(300_000L, BrowserCliSupport.resolveTimeout(600_000L, properties));
        Assert.assertEquals(5_000L, BrowserCliSupport.resolveTimeout(5_000L, properties));
        // 字符串数字同样受理
        Assert.assertEquals(5_000L, BrowserCliSupport.resolveTimeout("5000", properties));
    }

    @Test
    public void fallsBackToDefaultTimeoutForNonPositiveInput() {
        OpenCliProperties properties = new OpenCliProperties();
        properties.setDefaultTimeoutMs(180_000L);
        properties.setMaxTimeoutMs(540_000L);

        Assert.assertEquals(180_000L, BrowserCliSupport.resolveTimeout(0, properties));
        Assert.assertEquals(180_000L, BrowserCliSupport.resolveTimeout(-5, properties));
        Assert.assertEquals(180_000L, BrowserCliSupport.resolveTimeout("abc", properties));
        Assert.assertEquals(180_000L, BrowserCliSupport.resolveTimeout(null, properties));
    }

    @Test
    public void usesSaneTimeoutFallbacksWhenPropertiesUnset() {
        OpenCliProperties properties = new OpenCliProperties();
        properties.setDefaultTimeoutMs(0L);
        properties.setMaxTimeoutMs(0L);

        // max 缺省 180s：显式超时仍受它钳制，fallback 只在没给超时时生效
        Assert.assertEquals(180_000L, BrowserCliSupport.resolveTimeout(999_999L, properties));
        Assert.assertEquals(60_000L, BrowserCliSupport.resolveTimeout(null, properties));
    }

    @Test
    public void resolvesCacheDirUnderConfiguredRoot() {
        OpenCliProperties properties = new OpenCliProperties();
        properties.setCacheDir("/tmp/opencli");

        String dir = BrowserCliSupport.resolveCacheDir(properties, "user-1");

        // Windows 上 Path.of 会把 "/tmp/opencli" 规范成 "	mp\opencli"，只断言两端组成
        Assert.assertTrue(dir.contains("opencli"));
        Assert.assertTrue(dir.endsWith("user-1"));
    }

    @Test
    public void rejectsUserIdThatEscapesCacheRoot() {
        OpenCliProperties properties = new OpenCliProperties();
        properties.setCacheDir("/tmp/opencli");

        Assert.assertThrows(IllegalArgumentException.class,
                () -> BrowserCliSupport.resolveCacheDir(properties, "../../evil"));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> BrowserCliSupport.resolveCacheDir(properties, ".."));
    }

    @Test
    public void fallsBackToUnknownForMissingUserId() {
        OpenCliProperties properties = new OpenCliProperties();
        properties.setCacheDir("/tmp/opencli");

        Assert.assertTrue(BrowserCliSupport.resolveCacheDir(properties, null).endsWith("unknown"));
        Assert.assertTrue(BrowserCliSupport.resolveCacheDir(properties, "  ").endsWith("unknown"));
    }

    @Test
    public void redactsCdpUrlFromStructuredStderrHints() {
        String cdpWsUrl = "ws://127.0.0.1:9222/devtools/browser/secret";
        CliResult result = CliResult.builder()
                .ok(false)
                .exitCode(1)
                .stderr("Hint: reconnect to " + cdpWsUrl + " first\nRetry: curl " + cdpWsUrl)
                .build();

        Map<String, Object> fields = BrowserCliSupport.toFields(result, cdpWsUrl);

        Assert.assertTrue(String.valueOf(fields.get("error_hint")).contains("[redacted]"));
        Assert.assertFalse(String.valueOf(fields.get("error_hint")).contains(cdpWsUrl));
        Assert.assertFalse(String.valueOf(fields.get("retry")).contains(cdpWsUrl));
    }

    @Test
    public void viewportUploadFailureIsReportedInsteadOfFakeAttachment() {
        // runtimeDependencies 缺失时 uploadPng 必然返回 null：模拟截图上传失败。
        AgentContext context = AgentContext.builder().requestId("req-viewport").build();
        Map<String, Object> fields = new LinkedHashMap<>();

        BrowserCliSupport.publishViewport(context, "{\"imageBase64\":\"aGVsbG8=\"}", fields);

        Assert.assertFalse("base64 仍从观察里移除", fields.containsKey("imageBase64"));
        Assert.assertFalse("没有成果物就不能写 artifactRef", fields.containsKey("artifactRef"));
        Assert.assertEquals(Boolean.TRUE, fields.get("image_upload_failed"));
        Assert.assertFalse("不能宣称已附加图片",
                String.valueOf(fields.get("message")).contains("attached as image media"));
    }

    @Test
    public void stringListRejectsPartialNonStringItems() {
        Assert.assertEquals(List.of("a", "b"), BrowserCliSupport.stringList(List.of("a", "b")));
        Assert.assertTrue(BrowserCliSupport.stringList(null).isEmpty());
        Assert.assertTrue(BrowserCliSupport.stringList(List.of()).isEmpty());
        // 混入非字符串即整体作废，避免把参数表猜成半可用的 argv
        Assert.assertTrue(BrowserCliSupport.stringList(List.of("a", 1)).isEmpty());
    }
}
