package org.wwz.ai.domain.agent.security;

import org.junit.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * 出站 URL 安全判定：地址类别而非字符串黑名单。
 */
public class OutboundUrlGuardTest {

    @Test
    public void publicLiteralHostsAreAllowed() {
        OutboundUrlGuard.validateHttpUrl("https://8.8.8.8/file.zip");
        OutboundUrlGuard.validateHttpUrl("http://172.32.0.1/x");
        OutboundUrlGuard.validateHttpUrl("https://[2001:4860:4860::8888]/x");
        OutboundUrlGuard.validateHttpUrl("https://[::ffff:808:808]/x");
        assertFalse(OutboundUrlGuard.isBlockedHostForDisplay("example.com"));
    }

    @Test
    public void loopbackAndPrivateLiteralsAreRejected() {
        assertRejected("http://127.0.0.1:8080/x");
        assertRejected("http://127.1/x");
        assertRejected("http://10.0.0.5/x");
        assertRejected("http://172.16.3.4/x");
        assertRejected("http://192.168.1.10/x");
        assertRejected("http://169.254.169.254/latest/meta-data");
        assertRejected("http://100.64.0.1/x");
        assertRejected("http://0.0.0.0/x");
        assertRejected("http://0.1.2.3/x");
        assertRejected("http://224.0.0.1/x");
    }

    @Test
    public void ipv6InternalCategoriesAreRejected() {
        assertRejected("http://[::1]/x");
        assertRejected("http://[::]/x");
        assertRejected("http://[fc00::1]/x");
        assertRejected("http://[fd12:3456::1]/x");
        assertRejected("http://[fe80::1]/x");
        assertRejected("http://[::ffff:127.0.0.1]/x");
        assertRejected("http://[64:ff9b::7f00:1]/x");
    }

    @Test
    public void nonCanonicalIpv4FormsAreRejected() {
        assertRejected("http://0177.0.0.1/x");
        assertRejected("http://0x7f.0.0.1/x");
        assertRejected("http://2130706433/x");
        assertRejected("http://127.1/x");
        assertRejected("http://999.1.1.1/x");
    }

    @Test
    public void trailingDotLocalhostAndInternalNamesAreRejected() {
        assertRejected("http://localhost:1601/x");
        assertRejected("http://localhost./x");
        assertRejected("http://metadata.google.internal/x");
        assertRejected("http://cute-tool.local/x");
        assertRejected("http://host.docker.internal/x");
        assertRejected("http://cute-tool/x");
    }

    @Test
    public void credentialsAndNonHttpSchemesAreRejected() {
        assertRejected("http://user:pass@8.8.8.8/x");
        assertRejected("file:///etc/passwd");
        assertRejected("ftp://8.8.8.8/x");
        assertRejected("javascript:alert(1)");
    }

    @Test
    public void displayCheckDoesNotResolveOrdinaryHostnames() {
        // 展示态不做 DNS：普通公网域名保持可达，避免链接展示被 DNS 抖动影响。
        assertFalse(OutboundUrlGuard.isBlockedHostForDisplay("owwzo.top"));
    }

    @Test
    public void anyPrivateAddressInDnsResultIsRejected() throws Exception {
        OutboundUrlGuard.HostResolver resolver = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("127.0.0.1")
        };
        assertRejected("https://example.com/skill.zip", resolver);
    }

    @Test
    public void validatedAddressesAreReturnedWithoutChangingOriginalHost() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        OutboundUrlGuard.ValidatedHttpUrl target = OutboundUrlGuard.resolveHttpUrl(
                "https://example.com/skill.zip",
                host -> new InetAddress[]{publicAddress});

        assertEquals("example.com", target.host());
        assertEquals("example.com", target.uri().getHost());
        assertEquals(List.of(publicAddress), target.addresses());
    }

    private static void assertRejected(String url) {
        assertRejected(url, InetAddress::getAllByName);
    }

    private static void assertRejected(String url, OutboundUrlGuard.HostResolver resolver) {
        try {
            OutboundUrlGuard.resolveHttpUrl(url, resolver);
            fail("should reject: " + url);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
