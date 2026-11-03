package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.security.OutboundUrlGuard;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 产物链接策略：决定 DB 里的 {@code preview_url} 能不能直接发给 QQ 用户。
 *
 * <p>生产环境 DB 里存的已经是公网地址（{@code FILE_SERVER_URL} 配成 {@code https://域名/tool/v1/file_tool}），
 * 直接透传即可；本地开发存的是 {@code http://127.0.0.1:1601/...}，对 QQ 用户毫无意义，只能降级成
 * 「只显示文件名」。配了 {@code publicBaseUrl}（内网穿透场景）时按 requestId + fileName 重建。</p>
 */
public final class ChannelUrlPolicy {

    /** 预览路径，与前端 {@code buildFilePreviewUrlForBrowser} 保持一致。 */
    private static final String PREVIEW_PATH = "/v1/file_tool/preview/";

    private final String publicBaseUrl;

    public ChannelUrlPolicy(String publicBaseUrl) {
        this.publicBaseUrl = StringUtils.isBlank(publicBaseUrl)
                ? ""
                : StringUtils.removeEnd(publicBaseUrl.trim(), "/");
    }

    public boolean hasPublicBase() {
        return !publicBaseUrl.isEmpty();
    }

    /**
     * 把产物地址转成可以贴进 QQ 消息的链接。
     *
     * @return 可点击地址；空串表示当前环境无法给出可达链接，调用方只展示文件名
     */
    public String toClickableUrl(String rawUrl, String requestId, String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        if (hasPublicBase() && StringUtils.isNotBlank(requestId)) {
            return publicBaseUrl + PREVIEW_PATH
                    + encodeSegment(requestId) + "/" + encodePath(fileName);
        }
        String trimmed = StringUtils.trimToEmpty(rawUrl);
        if (trimmed.isEmpty() || isUnreachable(trimmed)) {
            return "";
        }
        return trimmed;
    }

    /**
     * 判定地址对终端用户是否不可达。
     * <p>必须先解析出 host 再判断，不能对整串做字符串匹配——{@code https://example.com/127.0.0.1}
     * 是可达的。</p>
     */
    public static boolean isUnreachable(String url) {
        String trimmed = StringUtils.trimToEmpty(url);
        if (trimmed.isEmpty()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return true;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            // 相对路径没有 host，QQ 用户点不到。
            return true;
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return true;
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            return true;
        }
        // IPv6 字面量在 URI 里带方括号。
        String normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return isPrivateHost(normalized.toLowerCase());
    }

    private static boolean isPrivateHost(String host) {
        // 统一由 OutboundUrlGuard 判断地址类别：IPv4 非规范写法、IPv6 ULA/link-local/未指定、
        // IPv4-mapped、带尾点的 localhost 都在那里收口，避免两套规则漂移。
        return OutboundUrlGuard.isBlockedHostForDisplay(host);
    }

    private static String encodeSegment(String value) {
        // "." / ".." 是路径穿越段：它们经 URLEncoder 不会被转义，必须在拼接前消除。
        if (".".equals(value) || "..".equals(value)) {
            return "_";
        }
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** 按 {@code /} 切段后逐段编码，保留相对目录结构。 */
    private static String encodePath(String path) {
        String normalized = path.replace('\\', '/');
        StringBuilder out = new StringBuilder(normalized.length() + 16);
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(encodeSegment(segment));
        }
        return out.toString();
    }
}
