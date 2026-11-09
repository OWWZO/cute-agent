package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.wwz.ai.Application;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.memory.WorkingMemoryMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemoryProjector;
import org.wwz.ai.domain.agent.memory.WorkingMemoryScopes;
import org.wwz.ai.domain.agent.memory.WorkingMemoryTurn;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryScope;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryStore;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.infrastructure.dao.cute.IDialogueSessionLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryMessageDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryTurnDao;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Eval-only seed entry point for cross-session working memory.
 *
 * <p>The CLI stays in the test source tree and only writes the evaluation database.
 * It uses the same session, turn, message and curated-memory tables as production,
 * but accepts an explicit historical timestamp so LongMemEval sessions retain
 * their official dates.</p>
 */
public final class EvalMemorySeedCli {

    private static final DateTimeFormatter LONG_MEMORY_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd (EEE) HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SLASH_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final DateTimeFormatter SPACE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final WorkingMemoryProjector PROJECTOR = new WorkingMemoryProjector();

    private EvalMemorySeedCli() {
    }

    public static void main(String[] args) throws Exception {
        String springProfile = "eval";
        for (int i = 0; i < args.length; i++) {
            if ("--spring-profile".equals(args[i]) && i + 1 < args.length) {
                springProfile = args[++i];
            }
        }
        if ("dev".equalsIgnoreCase(springProfile) || "prod".equalsIgnoreCase(springProfile)) {
            throw new IllegalArgumentException("memory seed refuses dev/prod Spring profiles");
        }
        JSONObject request = JSON.parseObject(
                new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(springProfile);
        try (ConfigurableApplicationContext context = application.run()) {
            System.out.println(seed(request, context).toJSONString());
        }
    }

    static JSONObject seed(JSONObject request, ConfigurableApplicationContext context) {
        validate(request);
        String userId = required(request, "userId");
        String namespace = request.getString("namespace");
        IDialogueSessionLedgerDao sessionDao =
                context.getBean(IDialogueSessionLedgerDao.class);
        IWorkingMemoryTurnDao turnDao =
                context.getBean(IWorkingMemoryTurnDao.class);
        IWorkingMemoryMessageDao messageDao =
                context.getBean(IWorkingMemoryMessageDao.class);

        JSONArray sessions = request.getJSONArray("sessions");
        JSONObject result = new JSONObject();
        JSONArray seededSessions = new JSONArray();
        JSONArray skippedSessions = new JSONArray();
        int messageCount = 0;
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            String sessionId = required(session, "sessionId");
            String title = session.getString("title");
            LocalDateTime timestamp = parseTimestamp(required(session, "timestamp"));
            List<Message> messages = toMessages(session.getJSONArray("turns"), sessionId);
            String requestId = seedRequestId(namespace, sessionId);

            sessionDao.insertSessionIfAbsent(DialogueSessionUpsertRecord.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .sessionKind("conversation")
                    .rootSessionId(sessionId)
                    .title(title == null || title.isBlank()
                            ? "Eval memory seed " + sessionId
                            : title)
                    .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                    .latestRequestId(requestId)
                    .latestQueryText(messages.isEmpty() ? null : messages.get(0).getContent())
                    .runCount(1)
                    .finishedRunCount(1)
                    .failedRunCount(0)
                    .startedAt(timestamp)
                    .lastActiveAt(timestamp)
                    .deepThink(0)
                    .build());

            if (turnDao.selectByRequestId(requestId) != null) {
                skippedSessions.add(sessionId);
                continue;
            }
            List<WorkingMemoryMessage> rows = PROJECTOR.project(
                    messages,
                    sessionId,
                    WorkingMemoryScopes.MAIN,
                    requestId,
                    null);
            int tokenEstimate = rows.stream()
                    .mapToInt(row -> estimateTokens(row.getContent()))
                    .sum();
            WorkingMemoryTurn turn = WorkingMemoryTurn.builder()
                    .sessionId(sessionId)
                    .memoryScope(WorkingMemoryScopes.MAIN)
                    .requestId(requestId)
                    .turnSeq(1)
                    .entryAgent("eval-seed")
                    .status(WorkingMemoryTurn.STATUS_READY)
                    .schemaVersion(1)
                    .messageCount(rows.size())
                    .tokenEstimate(tokenEstimate)
                    .startedAt(timestamp)
                    .finishedAt(timestamp)
                    .deleted(0)
                    .build();
            turnDao.insertTurn(turn);
            if (turn.getId() == null) {
                throw new IllegalStateException("seed turn id was not generated: " + sessionId);
            }
            for (WorkingMemoryMessage row : rows) {
                row.setTurnId(turn.getId());
                row.setMemoryScope(WorkingMemoryScopes.MAIN);
                row.setTokenEstimate(estimateTokens(row.getContent()));
            }
            if (!rows.isEmpty()) {
                messageDao.batchInsertMessages(rows);
                messageCount += rows.size();
            }
            seededSessions.add(sessionId);
        }

        int curated = seedCurated(request, context, userId);
        result.put("ok", true);
        result.put("schemaVersion", "1.0");
        result.put("userId", userId);
        result.put("seededSessions", seededSessions);
        result.put("skippedSessions", skippedSessions);
        result.put("seededMessages", messageCount);
        result.put("curatedEntries", curated);
        return result;
    }

    static List<Message> toMessages(JSONArray turns, String sessionId) {
        if (turns == null || turns.isEmpty()) {
            throw new IllegalArgumentException("memory seed session has no turns: " + sessionId);
        }
        List<Message> messages = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            JSONObject row = turns.getJSONObject(i);
            String role = required(row, "role").trim().toUpperCase(Locale.ROOT);
            String content = row.getString("content");
            if (content == null) {
                throw new IllegalArgumentException(
                        "memory seed content is null: " + sessionId + "#" + i);
            }
            String origin = sessionId + ":" + i;
            switch (role) {
                case "SYSTEM" -> messages.add(Message.systemMessage(content, null, origin));
                case "USER" -> messages.add(Message.userMessage(content, null, origin));
                case "ASSISTANT" -> messages.add(Message.assistantMessage(
                        content, null, null, origin));
                default -> throw new IllegalArgumentException(
                        "unsupported memory seed role: " + role);
            }
        }
        return messages;
    }

    static LocalDateTime parseTimestamp(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("timestamp must not be blank");
        }
        for (DateTimeFormatter formatter : List.of(
                LONG_MEMORY_DATE,
                SLASH_DATE,
                SPACE_DATE)) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next official/eval timestamp shape.
            }
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Fall through to the stable error below.
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("unsupported timestamp: " + text, e);
        }
    }

    static String seedRequestId(String namespace, String sessionId) {
        String prefix = namespace == null || namespace.isBlank()
                ? "eval-seed"
                : namespace.trim();
        String value = prefix + ":" + sessionId;
        if (value.length() <= 64) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "eval-seed-" + HexFormat.of().formatHex(digest).substring(0, 54);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void validate(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("memory seed payload is required");
        }
        required(request, "userId");
        JSONArray sessions = request.getJSONArray("sessions");
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("memory seed requires a non-empty sessions array");
        }
        Set<String> sessionIds = new HashSet<>();
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            String sessionId = required(session, "sessionId");
            if (!sessionId.startsWith("eval-")) {
                throw new IllegalArgumentException(
                        "memory seed sessionId must start with eval-: " + sessionId);
            }
            if (!sessionIds.add(sessionId)) {
                throw new IllegalArgumentException("duplicate memory seed sessionId: " + sessionId);
            }
            required(session, "timestamp");
            JSONArray turns = session.getJSONArray("turns");
            if (turns == null || turns.isEmpty()) {
                throw new IllegalArgumentException("memory seed session has no turns: " + sessionId);
            }
        }
    }

    private static int seedCurated(JSONObject request,
                                   ConfigurableApplicationContext context,
                                   String userId) {
        JSONArray entries = request.getJSONArray("curatedEntries");
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        CuratedMemoryStore store = context.getBeanProvider(CuratedMemoryStore.class)
                .getIfAvailable();
        if (store == null) {
            throw new IllegalStateException(
                    "curated memory was requested but memory.ltm is disabled");
        }
        int inserted = 0;
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            CuratedMemoryScope scope = CuratedMemoryScope.fromCode(
                    required(entry, "scope"));
            var write = store.add(
                    LtmOwner.user(userId),
                    scope,
                    required(entry, "content"),
                    null,
                    "eval-memory-seed",
                    "system");
            if (write.isSuccess()) {
                inserted++;
            }
        }
        return inserted;
    }

    private static int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, (content.length() + 3) / 4);
    }

    private static String required(JSONObject row, String key) {
        String value = row == null ? null : row.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }
}
