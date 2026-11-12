package org.wwz.ai.domain.agent.security;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.net.SocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;

/**
 * 只连接 {@link OutboundUrlGuard} 已验证地址的 HTTP 下载器。
 *
 * <p>请求 URL 始终保持原始主机名，因此 Host 头和 HTTPS SNI / 证书校验仍按原主机执行；
 * 自定义 DNS 只返回校验阶段得到的固定地址，连接阶段不会再次解析域名。</p>
 */
public final class PinnedHttpFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .callTimeout(CALL_TIMEOUT)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();

    private final OutboundUrlGuard.HostResolver resolver;
    private final SocketFactory socketFactory;

    PinnedHttpFetcher(OutboundUrlGuard.HostResolver resolver, SocketFactory socketFactory) {
        this.resolver = resolver;
        this.socketFactory = socketFactory;
    }

    /**
     * 下载 URL，逐跳校验重定向并限制响应体大小。
     */
    public static byte[] fetch(String rawUrl, int maxRedirects, long maxBytes) throws IOException {
        return new PinnedHttpFetcher(InetAddress::getAllByName, SocketFactory.getDefault())
                .fetchUrl(rawUrl, maxRedirects, maxBytes);
    }

    byte[] fetchUrl(String rawUrl, int maxRedirects, long maxBytes) throws IOException {
        String currentUrl = rawUrl;
        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            OutboundUrlGuard.ValidatedHttpUrl target =
                    OutboundUrlGuard.resolveHttpUrl(currentUrl, resolver);
            FetchResult result = fetchOnce(target, maxBytes);
            if (isRedirect(result.status())) {
                if (result.location() == null || result.location().isBlank()) {
                    throw new IOException("下载失败：重定向缺少 Location");
                }
                URI next = target.uri().resolve(result.location().trim());
                currentUrl = next.toString();
                continue;
            }
            if (result.status() != 200) {
                throw new IOException("下载失败：HTTP " + result.status());
            }
            return result.body();
        }
        throw new IOException("下载失败：重定向次数超过 " + maxRedirects);
    }

    private FetchResult fetchOnce(OutboundUrlGuard.ValidatedHttpUrl target, long maxBytes)
            throws IOException {
        OkHttpClient client = BASE_CLIENT.newBuilder()
                .dns(pinnedDns(target))
                .socketFactory(socketFactory)
                .build();
        Request request = new Request.Builder()
                .url(target.uri().toString())
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (isRedirect(response.code())) {
                return new FetchResult(
                        response.code(), response.header("Location"), new byte[0]);
            }
            ResponseBody body = response.body();
            if (response.code() != 200) {
                return new FetchResult(response.code(), null, new byte[0]);
            }
            if (body == null) {
                return new FetchResult(response.code(), null, new byte[0]);
            }
            if (body.contentLength() > maxBytes) {
                throw new IOException("技能包超过 32MB");
            }
            try (InputStream input = body.byteStream()) {
                return new FetchResult(response.code(), null, readLimited(input, maxBytes));
            }
        }
    }

    private static Dns pinnedDns(OutboundUrlGuard.ValidatedHttpUrl target) {
        return hostname -> {
            if (!target.host().equals(hostname.toLowerCase(Locale.ROOT))) {
                throw new UnknownHostException(hostname);
            }
            return target.addresses();
        };
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] readLimited(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("技能包超过 32MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private record FetchResult(int status, String location, byte[] body) {
    }
}
