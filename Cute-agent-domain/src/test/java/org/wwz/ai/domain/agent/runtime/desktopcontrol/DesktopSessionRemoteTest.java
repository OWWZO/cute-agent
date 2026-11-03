package org.wwz.ai.domain.agent.runtime.desktopcontrol;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;

/**
 * 远端返回的桌面 URL 在交给客户端前必须校验 scheme 与地址类别。
 */
public class DesktopSessionRemoteTest {

    @Test
    public void rejectsJavascriptAndFileSchemes() {
        assertRejected("javascript:alert(1)");
        assertRejected("file:///etc/passwd");
    }

    @Test
    public void rejectsInternalHosts() {
        assertRejected("http://127.0.0.1/vnc.html");
        assertRejected("http://169.254.169.254/vnc.html");
        assertRejected("https://[::1]/vnc.html");
    }

    @Test
    public void acceptsPublicUrl() throws Exception {
        DesktopSessionRemote.OpenResult result = open("https://8.8.8.8/vnc.html?autoconnect=1");
        Assert.assertEquals("https://8.8.8.8/vnc.html?autoconnect=1", result.getUrl());
    }

    private static void assertRejected(String url) {
        try {
            open(url);
            Assert.fail("should reject: " + url);
        } catch (Exception expected) {
            Assert.assertTrue(expected.getMessage() != null
                    && expected.getMessage().contains("不安全的 url"));
        }
    }

    private static DesktopSessionRemote.OpenResult open(String url) throws Exception {
        RemoteHttpPort port = request -> "{\"url\":\"" + url + "\",\"holdUntil\":123.0}";
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://cute-tool");
        return DesktopSessionRemote.open(port, config, "req-1", "sess-1", "owner-1", 60);
    }
}
