package org.wwz.ai.domain.agent.runtime.tool.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpResponse;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.access.ToolAccessPolicy;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WebFetch：调用 Python 抓取正文 → 用 prompt 经小模型提炼。
 * 网页抓取和正文提取委托给 cute-tool /v1/tool/web_fetch；本端只负责 prompt 提取。
 */
@Slf4j
public class WebFetchTool implements BaseTool {

    public static final String TOOL_NAME = "WebFetch";

    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_MARKDOWN_LENGTH = 100_000;
    private static final long WEB_FETCH_SERVICE_TIMEOUT_SECONDS = 120L;
    private static final int EXTRACT_TIMEOUT_SECONDS = 90;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return """
                IMPORTANT: WebFetch WILL FAIL for authenticated or private URLs. Prefer specialized MCP tools for GitHub/Confluence/etc.

                Fetches and extracts content from a URL through the cute-tool Python service, then processes it with a prompt using a secondary model.
                - Inputs: url (required), prompt (required — what to extract/analyze)
                - Python handles redirects and HTML body extraction
                - The extracted page content is analyzed only against the provided prompt
                - Read-only; does not write files
                """;
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("type", "string");
        url.put("description", "The URL to fetch content from (http/https)");

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("description", "The prompt to run on the fetched content (what to extract or analyze)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", url);
        properties.put("prompt", prompt);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("url", "prompt"));
        return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input, AgentContext context) {
        long start = System.currentTimeMillis();
        String rawUrl = "";
        String prompt = "";
        ToolAccessPolicy.WebFetchDecision access = null;
        try {
            Map<String, Object> params = ToolArgs.mapStrict(input);
            rawUrl = StringUtils.trimToEmpty(valueAsString(params.get("url")));
            prompt = StringUtils.trimToEmpty(valueAsString(params.get("prompt")));
            if (StringUtils.isBlank(rawUrl)) {
                return failure("WebFetch 失败：url 不能为空", rawUrl, prompt);
            }
            if (StringUtils.isBlank(prompt)) {
                return failure("WebFetch 失败：prompt 不能为空（需说明要从页面提取/分析什么）", rawUrl, prompt);
            }

            validateUrl(rawUrl);
            ToolAccessPolicy accessPolicy = context == null
                    ? null
                    : context.getToolAccessPolicy();
            access = accessPolicy == null
                    ? null
                    : accessPolicy.beforeWebFetch(rawUrl);
            if (access != null && !access.allowed()) {
                Map<String, Object> detail = failureDetails(rawUrl, prompt);
                detail.put("failureClass", access.reason());
                detail.put("retryable", Boolean.FALSE);
                detail.put("attempt", access.attempt());
                detail.put("hint", access.hint());
                return ToolResultPayload.failureFrom("WebFetch 失败：" + access.hint(), detail);
            }

            FetchedPage fetch = fetchFromPython(rawUrl, context);
            if (accessPolicy != null) {
                accessPolicy.recordWebFetchSuccess();
            }
            String markdown = truncateContent(fetch.content());
            ExtractOutcome extracted = applyPromptToContent(prompt, markdown, context);
            long durationMs = System.currentTimeMillis() - start;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tool", "web_fetch");
            data.put("ok", Boolean.TRUE);
            data.put("url", fetch.finalUrl());
            data.put("status", fetch.statusCode());
            if (StringUtils.isNotBlank(fetch.statusText())) {
                data.put("statusText", fetch.statusText());
            }
            data.put("bytes", fetch.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            data.put("durationMs", durationMs);
            data.put("prompt", prompt);
            data.put("contentFormat", fetch.contentFormat());
            data.put("contentSource", fetch.contentSource());
            data.put("wordCount", fetch.wordCount());
            if (fetch.metadata() != null && !fetch.metadata().isEmpty()) {
                data.put("metadata", fetch.metadata());
            }
            data.put("content", extracted.content());
            data.put("degraded", extracted.degraded());
            if (extracted.degraded()) {
                data.put("extractError", extracted.errorSummary());
                data.put("hint", "Page fetch succeeded; model extract failed, returned truncated page text.");
            }
            return ToolResultPayload.fromData(data);
        } catch (FetchHttpException e) {
            log.warn("{} WebFetch remote response failed, url={}, status={}", requestId(context), e.url, e.statusCode);
            FetchFailureKind failureKind = classifyFailure(e.statusCode, e.responseBody);
            recordAccessFailure(context, failureKind);
            Map<String, Object> detail = failureDetails(rawUrl, prompt);
            detail.put("status", e.statusCode);
            detail.put("statusText", StringUtils.defaultString(e.statusText));
            detail.put("responseBody", e.responseBody);
            addFailurePolicy(detail, failureKind);
            if (access != null) {
                detail.put("attempt", access.attempt());
            }
            return ToolResultPayload.failureFrom("WebFetch 失败：" + e.getMessage(), detail);
        } catch (Exception e) {
            log.error("{} WebFetch execute error, input={}", requestId(context), input, e);
            FetchFailureKind failureKind = classifyFailure(0, rootCauseSummary(e));
            recordAccessFailure(context, failureKind);
            Map<String, Object> detail = failureDetails(rawUrl, prompt);
            addFailurePolicy(detail, failureKind);
            if (access != null) {
                detail.put("attempt", access.attempt());
            }
            return ToolResultPayload.failureFrom(
                    "WebFetch 失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()),
                    detail
            );
        }
    }

    private static void recordAccessFailure(AgentContext context, FetchFailureKind failureKind) {
        if (context != null
                && context.getToolAccessPolicy() != null
                && failureKind != null) {
            context.getToolAccessPolicy().recordWebFetchFailure(
                    failureKind.networkInfrastructure()
            );
        }
    }

    private static void addFailurePolicy(Map<String, Object> detail, FetchFailureKind failureKind) {
        FetchFailureKind kind = failureKind == null ? FetchFailureKind.other() : failureKind;
        detail.put("failureClass", kind.failureClass());
        detail.put("retryable", kind.retryable());
        detail.put("maxAttemptsPerUrl", ToolAccessPolicy.MAX_WEB_FETCH_ATTEMPTS_PER_URL);
        detail.put("hint", kind.hint());
    }

    private static FetchFailureKind classifyFailure(int statusCode, String responseBody) {
        String body = StringUtils.defaultString(responseBody).toLowerCase(Locale.ROOT);
        if (body.contains("cannot connect to host")
                || body.contains("connect call failed")
                || body.contains("connection refused")
                || body.contains("proxy")) {
            return FetchFailureKind.proxy();
        }
        if (body.contains("403") || body.contains("forbidden") || statusCode == 403) {
            return FetchFailureKind.forbidden();
        }
        if (body.contains("401") || body.contains("unauthorized") || statusCode == 401) {
            return FetchFailureKind.unauthorized();
        }
        if (body.contains("404") || body.contains("not found") || statusCode == 404) {
            return FetchFailureKind.notFound();
        }
        if (body.contains("429") || body.contains("rate limit") || statusCode == 429) {
            return FetchFailureKind.rateLimited();
        }
        if (body.contains("timeout")
                || body.contains("timed out")
                || statusCode == 408
                || statusCode == 504) {
            return FetchFailureKind.timeout();
        }
        if (statusCode >= 500 || body.contains("502") || body.contains("503")) {
            return FetchFailureKind.upstream();
        }
        return FetchFailureKind.other();
    }

    private FetchedPage fetchFromPython(String url, AgentContext context) throws Exception {
        CuteConfig config = context.requireRuntimeDependencies().requireCuteConfig();
        String endpoint = buildWebFetchEndpoint(config.getWebFetchUrl());
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("requestId", StringUtils.defaultIfBlank(requestId(context), "web-fetch"));
        requestBody.put("url", url);
        requestBody.put("timeoutSeconds", WEB_FETCH_SERVICE_TIMEOUT_SECONDS);

        RemoteHttpResponse response = context.requireRuntimeDependencies().requireRemoteHttpPort().executeDetailed(RemoteHttpRequest.builder()
                .method("POST")
                .url(endpoint)
                .headers(Map.of("Accept", "application/json", "Content-Type", "application/json"))
                .body(JSON.toJSONString(requestBody))
                .connectTimeoutSeconds(30L)
                .readTimeoutSeconds(WEB_FETCH_SERVICE_TIMEOUT_SECONDS)
                .writeTimeoutSeconds(30L)
                .callTimeoutSeconds(WEB_FETCH_SERVICE_TIMEOUT_SECONDS)
                .build());

        int statusCode = response.getStatusCode();
        String responseBody = StringUtils.defaultString(response.getBody());
        if (statusCode < 200 || statusCode >= 300) {
            throw new FetchHttpException(
                    statusCode,
                    url,
                    response.getStatusText(),
                    StringUtils.abbreviate(responseBody, 2000)
            );
        }

        JSONObject root;
        try {
            root = JSON.parseObject(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid web_fetch response from cute-tool", e);
        }
        if (root == null) {
            throw new IllegalStateException("Empty web_fetch response from cute-tool");
        }
        Integer resultCode = root.getInteger("code");
        if (resultCode != null && resultCode != 200) {
            throw new FetchHttpException(
                    resultCode,
                    url,
                    root.getString("message"),
                    StringUtils.abbreviate(responseBody, 2000)
            );
        }

        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            throw new IllegalStateException("web_fetch response does not contain data");
        }
        String content = StringUtils.trimToEmpty(data.getString("content"));
        if (content.isBlank()) {
            throw new IllegalStateException("web_fetch response content is empty");
        }
        Integer fetchedStatusCode = data.getInteger("statusCode");
        Integer wordCount = data.getInteger("wordCount");
        return new FetchedPage(
                StringUtils.defaultIfBlank(data.getString("finalUrl"), url),
                fetchedStatusCode == null ? 200 : fetchedStatusCode,
                StringUtils.defaultString(data.getString("statusText")),
                content,
                StringUtils.defaultString(data.getString("contentFormat")),
                StringUtils.defaultString(data.getString("contentSource")),
                wordCount == null ? 0 : wordCount,
                data.getJSONObject("metadata")
        );
    }

    private static String buildWebFetchEndpoint(String configuredUrl) {
        String base = StringUtils.removeEnd(StringUtils.trimToEmpty(configuredUrl), "/");
        if (base.isBlank()) {
            throw new IllegalStateException("autobots.autoagent.web_fetch_url is not configured");
        }
        if (base.endsWith("/v1/tool/web_fetch")) {
            return base;
        }
        return base + "/v1/tool/web_fetch";
    }

    private ExtractOutcome applyPromptToContent(String prompt, String markdownContent, AgentContext context) {
        String modelPrompt = """
                Web page content:
                ---
                %s
                ---

                %s

                Provide a concise response based only on the content above.
                - Prefer short quotes; do not dump the entire page
                - Use quotation marks for exact language from the source
                """.formatted(markdownContent, prompt);

        try {
            CuteConfig config = context.requireRuntimeDependencies().requireCuteConfig();
            String modelName = StringUtils.defaultIfBlank(config.getSummaryModelName(), config.getReactModelName());
            LLM llm = new LLM(modelName, "", context.getRuntimeDependencies());
            // Prefer stream aggregation: some OpenAI-compatible gateways return empty/truncated
            // non-stream JSON bodies that Spring AI cannot deserialize as ChatCompletion.
            String answer = llm.ask(
                    context,
                    Collections.singletonList(Message.userMessage(modelPrompt, null)),
                    Collections.emptyList(),
                    true,
                    false,
                    0.0
            ).get(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ExtractOutcome.success(StringUtils.defaultIfBlank(answer, "No response from model"));
        } catch (Exception e) {
            String root = rootCauseSummary(e);
            log.warn("{} WebFetch model extract failed, degrade to page text, root={}", requestId(context), root, e);
            String degraded = """
                    [WebFetch degraded: model extract failed]
                    error: %s
                    prompt: %s

                    page text:
                    %s
                    """.formatted(root, prompt, markdownContent);
            return ExtractOutcome.degraded(degraded, root);
        }
    }

    private static String rootCauseSummary(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = StringUtils.defaultIfBlank(cursor.getMessage(), cursor.getClass().getSimpleName());
        return cursor.getClass().getSimpleName() + ": " + message;
    }

    private record ExtractOutcome(String content, boolean degraded, String errorSummary) {
        private static ExtractOutcome success(String content) {
            return new ExtractOutcome(content, false, null);
        }

        private static ExtractOutcome degraded(String content, String errorSummary) {
            return new ExtractOutcome(content, true, errorSummary);
        }
    }

    private static String truncateContent(String content) {
        if (content == null || content.length() <= MAX_MARKDOWN_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_MARKDOWN_LENGTH) + "\n\n[Content truncated due to length...]";
    }

    private static void validateUrl(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("URL too long (max " + MAX_URL_LENGTH + ")");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only http/https URLs are supported");
        }
        if (StringUtils.isNotBlank(uri.getUserInfo())) {
            throw new IllegalArgumentException("URL must not contain username/password");
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("URL hostname is required");
        }
        // 允许 localhost / 裸主机名用于内网与测试；公网域名仍应含点
        if (!"localhost".equalsIgnoreCase(host) && !host.contains(".")) {
            throw new IllegalArgumentException("URL hostname is not publicly resolvable style: " + host);
        }
    }

    private ToolResultPayload failure(String message, String url, String prompt) {
        return ToolResultPayload.failureFrom(message, failureDetails(url, prompt));
    }

    private Map<String, Object> failureDetails(String url, String prompt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "tool_error");
        detail.put("tool", "web_fetch");
        if (StringUtils.isNotBlank(url)) {
            detail.put("url", url);
        }
        if (StringUtils.isNotBlank(prompt)) {
            detail.put("prompt", prompt);
        }
        return detail;
    }


    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String requestId(AgentContext context) {
        return context == null ? "unknown" : StringUtils.defaultString(context.getRequestId(), "unknown");
    }

    private static final class FetchHttpException extends IllegalStateException {
        private final int statusCode;
        private final String url;
        private final String statusText;
        private final String responseBody;

        private FetchHttpException(int statusCode,
                                   String url,
                                   String statusText,
                                   String responseBody) {
            super("HTTP " + statusCode + " for " + url + ": " + responseBody);
            this.statusCode = statusCode;
            this.url = url;
            this.statusText = statusText;
            this.responseBody = responseBody;
        }
    }

    private record FetchFailureKind(String failureClass,
                                    boolean networkInfrastructure,
                                    boolean retryable,
                                    String hint) {
        private static FetchFailureKind proxy() {
            return new FetchFailureKind(
                    "network_proxy",
                    true,
                    false,
                    "代理或网络链路不可用。不要重试同一地址；请换信息源或基于已有证据输出最终答案。"
            );
        }

        private static FetchFailureKind timeout() {
            return new FetchFailureKind(
                    "network_timeout",
                    true,
                    true,
                    "抓取超时。最多再尝试一次，之后必须换信息源或收尾。"
            );
        }

        private static FetchFailureKind upstream() {
            return new FetchFailureKind(
                    "upstream_error",
                    true,
                    true,
                    "上游服务异常。最多再尝试一次；连续失败后不要继续抓网页。"
            );
        }

        private static FetchFailureKind forbidden() {
            return new FetchFailureKind(
                    "access_forbidden",
                    false,
                    false,
                    "目标站点拒绝访问。不要重复抓同一 URL，请换公开信息源。"
            );
        }

        private static FetchFailureKind unauthorized() {
            return new FetchFailureKind(
                    "access_unauthorized",
                    false,
                    false,
                    "目标页面需要登录或授权。不要重复抓取，请换公开信息源。"
            );
        }

        private static FetchFailureKind notFound() {
            return new FetchFailureKind(
                    "not_found",
                    false,
                    false,
                    "目标页面不存在。不要重复抓同一 URL。"
            );
        }

        private static FetchFailureKind rateLimited() {
            return new FetchFailureKind(
                    "rate_limited",
                    false,
                    true,
                    "目标站点限流。最多再尝试一次，并优先换信息源。"
            );
        }

        private static FetchFailureKind other() {
            return new FetchFailureKind(
                    "http_error",
                    false,
                    false,
                    "抓取失败。不要重复相同请求，请换信息源或收尾。"
            );
        }
    }

    private record FetchedPage(
            String finalUrl,
            int statusCode,
            String statusText,
            String content,
            String contentFormat,
            String contentSource,
            int wordCount,
            JSONObject metadata
    ) {
    }
}
