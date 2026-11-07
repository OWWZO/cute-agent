package org.wwz.ai.domain.agent.runtime.tool.access;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * run 级工具访问策略：限制同一 URL 的重复抓取，并在连续网络故障后打开断路。
 *
 * <p>目的是让 Agent 在网络基础设施故障时快速换路或收尾，而不是把预算消耗在
 * 同一代理错误的反复重试上。子 Agent 共享父实例。</p>
 */
public final class ToolAccessPolicy {

    public static final int MAX_WEB_FETCH_ATTEMPTS_PER_URL = 2;
    public static final int NETWORK_CIRCUIT_FAILURE_THRESHOLD = 2;

    public record WebFetchDecision(boolean allowed,
                                   int attempt,
                                   String reason,
                                   String hint) {
    }

    private final Map<String, AtomicInteger> webFetchAttempts = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveNetworkFailures = new AtomicInteger();
    private final AtomicReference<String> webFetchCircuitReason = new AtomicReference<>();

    public WebFetchDecision beforeWebFetch(String url) {
        String circuit = webFetchCircuitReason.get();
        if (circuit != null) {
            return new WebFetchDecision(
                    false,
                    0,
                    "network_circuit_open",
                    "WebFetch 网络链路已连续失败，禁止继续抓网页。请改用 WebSearch 或基于已有证据输出最终答案。"
            );
        }
        String key = normalizeUrl(url);
        AtomicInteger counter = webFetchAttempts.computeIfAbsent(key, ignored -> new AtomicInteger());
        int attempt = counter.incrementAndGet();
        if (attempt > MAX_WEB_FETCH_ATTEMPTS_PER_URL) {
            return new WebFetchDecision(
                    false,
                    attempt,
                    "url_attempt_limit",
                    "同一 URL 已达到抓取上限。不要重复抓这个地址；请换信息源或输出最终答案。"
            );
        }
        return new WebFetchDecision(true, attempt, null, null);
    }

    public void recordWebFetchSuccess() {
        consecutiveNetworkFailures.set(0);
    }

    public void recordWebFetchFailure(boolean networkFailure) {
        if (!networkFailure) {
            return;
        }
        int failures = consecutiveNetworkFailures.incrementAndGet();
        if (failures >= NETWORK_CIRCUIT_FAILURE_THRESHOLD) {
            webFetchCircuitReason.compareAndSet(null, "network_transport");
        }
    }

    public boolean isWebFetchCircuitOpen() {
        return webFetchCircuitReason.get() != null;
    }

    public int getConsecutiveNetworkFailures() {
        return consecutiveNetworkFailures.get();
    }

    private static String normalizeUrl(String url) {
        String value = url == null ? "" : url.trim();
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
            return scheme + "://" + host + path + query;
        } catch (Exception ignored) {
            return value;
        }
    }
}
