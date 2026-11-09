package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.wwz.ai.Application;
import org.wwz.ai.domain.agent.memory.CompactionBudget;
import org.wwz.ai.domain.agent.memory.CompactionPrompt;
import org.wwz.ai.domain.agent.memory.SessionContextCompactionService;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactionEvent;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactor;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.infrastructure.cute.service.impl.SessionContextCompactionServiceImpl;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryCompactionDao;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CompactBench / 内部压缩评测的协议适配入口。
 *
 * <p>只在 CLI 中做 JSON 与 Message 的转换；压缩算法、失败降级和审计仍走生产
 * {@link SessionContextCompactionServiceImpl}。输入来自 stdin，输出写到 stdout。</p>
 */
public final class EvalCompactionCli {

    private EvalCompactionCli() {
    }

    public static void main(String[] args) {
        String springProfile = "eval";
        for (int i = 0; i < args.length; i++) {
            if ("--spring-profile".equals(args[i]) && i + 1 < args.length) {
                springProfile = args[++i];
            }
        }
        if ("dev".equalsIgnoreCase(springProfile) || "prod".equalsIgnoreCase(springProfile)) {
            throw new IllegalArgumentException("compaction eval refuses dev/prod Spring profiles");
        }

        // 手动调用压缩服务；禁用 Agent 自身的自动压缩，避免同一请求二次压缩。
        System.setProperty("autobots.autoagent.compaction.enabled", "false");
        try {
            JSONObject request = JSON.parseObject(
                    new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
            SpringApplication application = new SpringApplication(Application.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles(springProfile);
            try (ConfigurableApplicationContext context = application.run()) {
                JSONObject response = compress(request, context);
                System.out.println(response.toJSONString());
            }
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("schemaVersion", "1.0");
            error.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println(error.toJSONString());
            System.exit(1);
        }
    }

    static JSONObject compress(JSONObject request, ConfigurableApplicationContext context) {
        String sessionId = required(request, "sessionId");
        String requestId = required(request, "requestId");
        List<Message> before = toMessages(request.getJSONArray("messages"));
        if (before.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        CompactionBudget requestedBudget = toBudget(request.getJSONObject("budget"));
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        CompactionBudget budget = request.getBooleanValue("force")
                ? requestedBudget.toBuilder()
                        .contextWindow(Math.max(2, compactor.estimateTokens(before)))
                        .build()
                : requestedBudget;
        SessionWorkingMemoryService workingMemory =
                context.getBean(SessionWorkingMemoryService.class);
        AtomicReference<WorkingMemoryCompactionEvent> inserted = new AtomicReference<>();
        IWorkingMemoryCompactionDao recordingDao;
        if (request.getBooleanValue("persistAudit")) {
            IWorkingMemoryCompactionDao persistentDao =
                    context.getBean(IWorkingMemoryCompactionDao.class);
            recordingDao = event -> {
                int rows = persistentDao.insertEvent(event);
                inserted.set(event);
                return rows;
            };
        } else {
            recordingDao = event -> {
                inserted.set(event);
                return 1;
            };
        }

        SessionContextCompactionService service =
                new SessionContextCompactionServiceImpl(
                        context.getBeanProvider(CuteRuntimeDependencies.class),
                        workingMemory,
                        recordingDao,
                        budget,
                        null) {
                    @Override
                    protected CompactionBudget resolveBudget() {
                        return budget;
                    }
                };
        List<Message> after = service.applyIfNeeded(
                sessionId,
                "main",
                requestId,
                before);
        WorkingMemoryCompactionEvent event = inserted.get();
        String summary = compactSummary(after);
        String summaryBody = checkpointBody(summary);

        JSONObject response = new JSONObject();
        response.put("schemaVersion", "1.0");
        response.put("sessionId", sessionId);
        response.put("requestId", requestId);
        response.put("messages", toJson(after));
        response.put("compactionTriggered", event != null);
        response.put("eventId", event == null || event.getId() == null
                ? null
                : "compaction:" + event.getId());
        response.put("status", event == null ? "not-triggered" : statusName(event.getStatus()));
        response.put("strategy", event == null ? "none" : event.getStrategy());
        response.put("failureReason", event == null ? null : event.getErrorMessage());
        response.put("beforeTokens", event == null
                ? compactor.estimateTokens(before)
                : event.getBeforeTokens());
        response.put("afterTokens", event == null
                ? compactor.estimateTokens(after)
                : event.getAfterTokens());
        response.put("summaryText", summaryBody);
        response.put("summaryHash", summaryBody == null ? null : "sha256:" + sha256(summaryBody));
        return response;
    }

    static List<Message> toMessages(JSONArray rows) {
        if (rows == null) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String role = required(row, "role").trim().toUpperCase(Locale.ROOT);
            messages.add(Message.builder()
                    .role(RoleType.valueOf(role))
                    .content(row.getString("content"))
                    .reasoningContent(row.getString("reasoningContent"))
                    .base64Image(row.getString("base64Image"))
                    .toolCallId(row.getString("toolCallId"))
                    .toolCalls(toToolCalls(row.getJSONArray("toolCalls")))
                    .modelOnly(row.getBoolean("modelOnly"))
                    .originMessageKey(row.getString("originMessageKey"))
                    .build());
        }
        return List.copyOf(messages);
    }

    static JSONArray toJson(List<Message> messages) {
        JSONArray rows = new JSONArray();
        if (messages == null) {
            return rows;
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            JSONObject row = new JSONObject();
            row.put("role", message.getRole() == null
                    ? null
                    : message.getRole().name().toLowerCase(Locale.ROOT));
            row.put("content", message.getContent());
            row.put("reasoningContent", message.getReasoningContent());
            row.put("base64Image", message.getBase64Image());
            row.put("toolCallId", message.getToolCallId());
            row.put("toolCalls", toolCallsToJson(message.getToolCalls()));
            row.put("modelOnly", message.getModelOnly());
            row.put("originMessageKey", message.getOriginMessageKey());
            rows.add(row);
        }
        return rows;
    }

    static CompactionBudget toBudget(JSONObject raw) {
        CompactionBudget defaults = CompactionBudget.defaults();
        if (raw == null) {
            raw = new JSONObject();
        }
        return defaults.toBuilder()
                .enabled(bool(raw, "enabled", defaults.isEnabled()))
                .llmEnabled(bool(raw, "llmEnabled", defaults.isLlmEnabled()))
                .microEnabled(bool(raw, "microEnabled", defaults.isMicroEnabled()))
                .sessionMemoryEnabled(bool(
                        raw, "sessionMemoryEnabled", defaults.isSessionMemoryEnabled()))
                .contextWindow(integer(raw, "contextWindow", defaults.getContextWindow()))
                .thresholdPercent(decimal(
                        raw, "thresholdPercent", defaults.getThresholdPercent()))
                .keepMinTokens(integer(raw, "keepMinTokens", defaults.getKeepMinTokens()))
                .keepMinTextMessages(integer(
                        raw, "keepMinTextMessages", defaults.getKeepMinTextMessages()))
                .keepMaxTokens(integer(raw, "keepMaxTokens", defaults.getKeepMaxTokens()))
                .maxConsecutiveFailures(integer(
                        raw, "maxConsecutiveFailures", defaults.getMaxConsecutiveFailures()))
                .temperature(decimal(raw, "temperature", defaults.getTemperature()))
                .messageContentCharLimit(integer(
                        raw, "messageContentCharLimit", defaults.getMessageContentCharLimit()))
                .summaryTargetRatio(decimal(
                        raw, "summaryTargetRatio", defaults.getSummaryTargetRatio()))
                .protectFirstN(integer(raw, "protectFirstN", defaults.getProtectFirstN()))
                .protectLastN(integer(raw, "protectLastN", defaults.getProtectLastN()))
                .contentMaxChars(integer(
                        raw, "contentMaxChars", defaults.getContentMaxChars()))
                .contentHeadChars(integer(
                        raw, "contentHeadChars", defaults.getContentHeadChars()))
                .contentTailChars(integer(
                        raw, "contentTailChars", defaults.getContentTailChars()))
                .summaryInputMaxChars(integer(
                        raw, "summaryInputMaxChars", defaults.getSummaryInputMaxChars()))
                .summarizerTimeoutSeconds(integer(
                        raw,
                        "summarizerTimeoutSeconds",
                        defaults.getSummarizerTimeoutSeconds()))
                .microKeepRecentToolResults(integer(
                        raw,
                        "microKeepRecentToolResults",
                        defaults.getMicroKeepRecentToolResults()))
                .microToolResultMaxChars(integer(
                        raw,
                        "microToolResultMaxChars",
                        defaults.getMicroToolResultMaxChars()))
                .midRunEnabled(false)
                .persistProjection(false)
                .auditEnabled(true)
                .auditStoreMessages(false)
                .build();
    }

    static String compactSummary(List<Message> messages) {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        if (messages == null) {
            return null;
        }
        for (Message message : messages) {
            if (compactor.isCompactSummaryMessage(message)) {
                return message.getContent();
            }
        }
        return null;
    }

    static String checkpointBody(String handoff) {
        if (handoff == null) {
            return null;
        }
        String body = CompactionPrompt.unwrapHandoffBody(handoff);
        int heading = body.indexOf("## ");
        return heading > 0 ? body.substring(heading).trim() : body;
    }

    private static List<ToolCall> toToolCalls(JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject function = row.getJSONObject("function");
            calls.add(ToolCall.builder()
                    .id(row.getString("id"))
                    .type(row.getString("type"))
                    .function(function == null
                            ? null
                            : ToolCall.Function.builder()
                            .name(function.getString("name"))
                            .arguments(function.getString("arguments"))
                            .build())
                    .build());
        }
        return List.copyOf(calls);
    }

    private static JSONArray toolCallsToJson(List<ToolCall> calls) {
        JSONArray rows = new JSONArray();
        if (calls == null) {
            return rows;
        }
        for (ToolCall call : calls) {
            if (call == null) {
                continue;
            }
            JSONObject row = new JSONObject();
            row.put("id", call.getId());
            row.put("type", call.getType());
            if (call.getFunction() != null) {
                JSONObject function = new JSONObject();
                function.put("name", call.getFunction().getName());
                function.put("arguments", call.getFunction().getArguments());
                row.put("function", function);
            }
            rows.add(row);
        }
        return rows;
    }

    private static boolean bool(JSONObject raw, String key, boolean fallback) {
        return raw.containsKey(key) ? raw.getBooleanValue(key) : fallback;
    }

    private static int integer(JSONObject raw, String key, int fallback) {
        return raw.containsKey(key) ? raw.getIntValue(key) : fallback;
    }

    private static double decimal(JSONObject raw, String key, double fallback) {
        return raw.containsKey(key) ? raw.getDoubleValue(key) : fallback;
    }

    private static String required(JSONObject row, String key) {
        String value = row == null ? null : row.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    private static String statusName(Integer status) {
        if (status == null) {
            return "unknown";
        }
        return status == WorkingMemoryCompactionEvent.STATUS_SUCCESS ? "success" : "failed";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }
}
