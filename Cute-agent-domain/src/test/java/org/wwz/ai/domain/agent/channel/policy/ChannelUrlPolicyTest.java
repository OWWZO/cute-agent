package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelUrlPolicyTest {

    private static final String REQ = "im-abc123";

    @Test
    public void rebuildsWhenPublicBaseConfigured() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("https://example.com/tool");
        assertEquals(
                "https://example.com/tool/v1/file_tool/preview/im-abc123/report.md",
                policy.toClickableUrl("http://127.0.0.1:1601/v1/file_tool/preview/im-abc123/report.md",
                        REQ, "report.md"));
    }

    @Test
    public void trailingSlashOnBaseDoesNotDoubleUp() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("https://example.com/tool/");
        assertEquals(
                "https://example.com/tool/v1/file_tool/preview/im-abc123/a.md",
                policy.toClickableUrl(null, REQ, "a.md"));
    }

    @Test
    public void fallsBackToRawUrlWhenRequestIdMissing() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("https://example.com/tool");
        assertEquals(
                "https://cdn.example.com/x/a.md",
                policy.toClickableUrl("https://cdn.example.com/x/a.md", null, "a.md"));
    }

    @Test
    public void passesThroughPublicUrlWhenNoBaseConfigured() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("");
        String url = "https://example.com/tool/v1/file_tool/preview/im-abc123/a.md";
        assertEquals(url, policy.toClickableUrl(url, REQ, "a.md"));
    }

    @Test
    public void dropsLoopbackUrlWhenNoBaseConfigured() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy(null);
        assertEquals("", policy.toClickableUrl(
                "http://127.0.0.1:1601/v1/file_tool/preview/im-abc123/a.md", REQ, "a.md"));
        assertEquals("", policy.toClickableUrl(
                "http://localhost:1601/v1/file_tool/preview/im-abc123/a.md", REQ, "a.md"));
    }

    @Test
    public void privateRangesAndContainerHostsAreUnreachable() {
        assertTrue(ChannelUrlPolicy.isUnreachable("http://10.0.0.5/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://172.16.3.4/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://172.31.255.1/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://192.168.1.10/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://cute-tool/v1/file_tool/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://host.docker.internal/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://cute-tool.local/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("http://[::1]:1601/x"));
        assertTrue(ChannelUrlPolicy.isUnreachable("/v1/file_tool/preview/a/b.md"));
        assertTrue(ChannelUrlPolicy.isUnreachable(""));
        assertTrue(ChannelUrlPolicy.isUnreachable(null));
    }

    @Test
    public void publicHostsAreReachable() {
        assertFalse(ChannelUrlPolicy.isUnreachable("https://example.com/tool/x"));
        assertFalse(ChannelUrlPolicy.isUnreachable("https://owwzo.top/tool/x"));
        assertFalse(ChannelUrlPolicy.isUnreachable("http://8.8.8.8/x"));
        // 172.32 已经出了私网 B 段。
        assertFalse(ChannelUrlPolicy.isUnreachable("http://172.32.0.1/x"));
    }

    @Test
    public void loopbackInPathIsNotMistakenForLoopbackHost() {
        // 字符串匹配会误判，host 匹配不会。
        assertFalse(ChannelUrlPolicy.isUnreachable("https://example.com/127.0.0.1/x"));
    }

    @Test
    public void nonHttpSchemesAreUnreachable() {
        assertTrue(ChannelUrlPolicy.isUnreachable("file:///tmp/a.md"));
        assertTrue(ChannelUrlPolicy.isUnreachable("ftp://example.com/a.md"));
    }

    @Test
    public void chineseFileNameIsPercentEncoded() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("https://example.com/tool");
        assertEquals(
                "https://example.com/tool/v1/file_tool/preview/im-abc123/%E5%91%A8%E6%8A%A5.md",
                policy.toClickableUrl(null, REQ, "周报.md"));
    }

    @Test
    public void nestedFilePathKeepsDirectories() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("https://example.com/tool");
        assertEquals(
                "https://example.com/tool/v1/file_tool/preview/im-abc123/out/charts/a%20b.png",
                policy.toClickableUrl(null, REQ, "out/charts/a b.png"));
    }

    @Test
    public void blankFileNameYieldsNoUrl() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy("https://example.com/tool");
        assertEquals("", policy.toClickableUrl("https://example.com/x", REQ, "  "));
    }
}
