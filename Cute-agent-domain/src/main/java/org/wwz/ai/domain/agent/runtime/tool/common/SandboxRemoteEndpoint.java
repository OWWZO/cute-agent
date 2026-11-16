package org.wwz.ai.domain.agent.runtime.tool.common;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;

/** cute-tool 沙箱端点的统一校验，避免底层 HTTP 客户端抛出难以理解的 URL 异常。 */
public final class SandboxRemoteEndpoint {

    private SandboxRemoteEndpoint() {
    }

    public static String requireHttpBaseUrl(String rawUrl) {
        String value = StringUtils.trimToEmpty(rawUrl);
        if (value.isEmpty()) {
            throw new IllegalStateException("codeInterpreterUrl 未配置，无法调用沙箱");
        }
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("codeInterpreterUrl 格式无效，必须是 http/https 地址", e);
        }
        String scheme = StringUtils.lowerCase(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)
                || StringUtils.isBlank(uri.getHost())) {
            throw new IllegalStateException("codeInterpreterUrl 格式无效，必须是 http/https 地址");
        }
        return StringUtils.removeEnd(value, "/");
    }

    public static String toolUrl(String rawBaseUrl, String toolPath) {
        String baseUrl = requireHttpBaseUrl(rawBaseUrl);
        String path = StringUtils.prependIfMissing(StringUtils.trimToEmpty(toolPath), "/");
        return baseUrl + path;
    }
}
