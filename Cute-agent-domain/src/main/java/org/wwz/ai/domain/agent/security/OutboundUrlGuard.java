package org.wwz.ai.domain.agent.security;

import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 出站 URL 安全校验：统一判断「这个地址能不能被服务端主动访问」。
 *
 * <p>判定基于解析后的真实 IP 地址类别，而不是内网地址字符串黑名单。覆盖：
 * loopback / link-local / site-local(private) / multicast / 0.0.0.0 / IPv6 ULA /
 * IPv4-mapped / NAT64 等非公网段。同时拒绝非规范 IPv4 写法（八进制、十六进制、
 * 省略段、超范围）、带凭据的 URL 和非 http(s) scheme。</p>
 *
 * <p>{@link #validateHttpUrl(String)} 会做 DNS 解析并在解析失败时拒绝（fail closed），
 * 适用于服务端真正发起请求之前的校验；{@link #isBlockedHostForDisplay(String)} 不做 DNS，
 * 只用于「链接展示可达性」这类不产生网络请求的场景。</p>
 */
public final class OutboundUrlGuard {

    private static final Pattern NUMERIC_HOST = Pattern.compile("^[0-9.]+$");
    private static final Pattern HEX_IPV4_PART = Pattern.compile("^0[xX][0-9a-fA-F]+$");
    private static final String[] INTERNAL_HOST_SUFFIXES = {
            ".localhost", ".local", ".internal", ".home.arpa"
    };
    private static final String[] INTERNAL_HOST_NAMES = {
            "localhost", "localhost.localdomain", "metadata.google.internal", "metadata"
    };

    private OutboundUrlGuard() {
    }

    /**
     * 校验服务端即将访问的 http(s) URL。
     *
     * @return 通过校验的原始 URL
     * @throws IllegalArgumentException 地址非法或解析到非公网地址
     */
    public static String validateHttpUrl(String rawUrl) {
        URI uri = parseHttpUri(rawUrl);
        String host = stripBrackets(uri.getHost());
        if (isBlockedHostForDisplay(host)) {
            throw new IllegalArgumentException("目标地址指向内网或非法主机: " + host);
        }
        if (!isIpLiteral(host)) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("目标域名无法解析: " + host, e);
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new IllegalArgumentException("目标域名解析到非公网地址: " + host);
                }
            }
        }
        return rawUrl;
    }

    /**
     * 不做 DNS 的展示态判定：只拦截字面量内网地址、特殊内网域名和单段主机名。
     *
     * @return {@code true} 表示该 host 不应被当作可达公网地址
     */
    public static boolean isBlockedHostForDisplay(String host) {
        String normalized = normalizeHost(host);
        if (normalized.isEmpty()) {
            return true;
        }
        for (String name : INTERNAL_HOST_NAMES) {
            if (name.equals(normalized)) {
                return true;
            }
        }
        for (String suffix : INTERNAL_HOST_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        if (looksLikeNonCanonicalIpv4(normalized)) {
            return true;
        }
        if (isCanonicalIpv4(normalized)) {
            return isBlockedAddress(inetAddressUnchecked(normalized));
        }
        if (normalized.indexOf(':') >= 0) {
            InetAddress address = inetAddressUnchecked(normalized);
            // 解析不了的 IPv6 字面量按非法处理，避免依赖下游客户端的宽松解析。
            return address == null || isBlockedAddress(address);
        }
        // 无点的单段主机名只可能是容器 / 内网别名（cute-tool、host.docker.internal 之类）。
        return normalized.indexOf('.') < 0;
    }

    /** 判断已解析的地址是否属于不可出站的地址类别。 */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            // 0.0.0.0/8、运营商级 NAT、保留段与文档段都不是公网可达目标。
            if (first == 0 || first >= 240) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return true;
            }
            if (first == 192 && second == 0 && (third == 0 || third == 2)) {
                return true;
            }
            if (first == 198 && second == 51 && third == 100) {
                return true;
            }
            return first == 203 && second == 0 && third == 113;
        }
        if (bytes.length == 16) {
            // IPv6 ULA fc00::/7。
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // 文档段 2001:db8::/32。
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
                return true;
            }
            byte[] embedded = extractEmbeddedIpv4(bytes);
            if (embedded != null) {
                return isBlockedAddress(inetAddressUnchecked(embedded));
            }
        }
        return false;
    }

    /** 解析为 http(s) URI；拒绝非规范写法、带凭据和缺失 host 的 URL。 */
    public static URI parseHttpUri(String rawUrl) {
        String trimmed = StringUtils.trimToEmpty(rawUrl);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 格式非法", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅允许 http/https: " + scheme);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URL 不允许携带凭据");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("URL 缺少合法 host");
        }
        return uri;
    }

    private static String normalizeHost(String host) {
        String normalized = StringUtils.trimToEmpty(host).toLowerCase(Locale.ROOT);
        normalized = stripBrackets(normalized);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String stripBrackets(String host) {
        if (host == null) {
            return "";
        }
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isIpLiteral(String host) {
        String normalized = normalizeHost(host);
        return isCanonicalIpv4(normalized)
                || looksLikeNonCanonicalIpv4(normalized)
                || normalized.indexOf(':') >= 0;
    }

    private static boolean isCanonicalIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 数字/十六进制字面量但不是规范 dotted-quad：八进制、十进制整数、十六进制、省略段、超范围。
     */
    private static boolean looksLikeNonCanonicalIpv4(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (NUMERIC_HOST.matcher(host).matches()) {
            return !isCanonicalIpv4(host);
        }
        String[] parts = host.split("\\.", -1);
        for (String part : parts) {
            if (HEX_IPV4_PART.matcher(part).matches()) {
                return true;
            }
        }
        return false;
    }

    private static byte[] extractEmbeddedIpv4(byte[] bytes) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                firstTenZero = false;
                break;
            }
        }
        if (firstTenZero && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        // IPv4-compatible（::a.b.c.d），以及 NAT64 64:ff9b::/96：末 4 字节内嵌 IPv4。
        boolean firstTwelveZero = firstTenZero && bytes[10] == 0 && bytes[11] == 0;
        boolean lastFourNonZero = bytes[12] != 0 || bytes[13] != 0 || bytes[14] != 0 || bytes[15] != 0;
        if (firstTwelveZero && lastFourNonZero) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        if ((bytes[0] & 0xff) == 0x00 && (bytes[1] & 0xff) == 0x64
                && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b) {
            for (int i = 4; i < 12; i++) {
                if (bytes[i] != 0) {
                    return null;
                }
            }
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return null;
    }

    private static InetAddress inetAddressUnchecked(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static InetAddress inetAddressUnchecked(byte[] raw) {
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
