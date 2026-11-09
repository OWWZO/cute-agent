package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class EvalMemorySeedCliTest {

    @Test
    public void parsesLongMemEvalOfficialTimestamp() {
        LocalDateTime timestamp = EvalMemorySeedCli.parseTimestamp(
                "2023/04/10 (Mon) 17:50");

        assertEquals(LocalDateTime.of(2023, 4, 10, 17, 50), timestamp);
    }

    @Test
    public void convertsOfficialHistoryTurnsWithoutChangingContent() {
        JSONArray rows = new JSONArray();
        rows.add(turn("user", "Remember ORCHID-42."));
        rows.add(turn("assistant", "I will remember ORCHID-42."));

        List<Message> messages = EvalMemorySeedCli.toMessages(
                rows,
                "eval-longmemeval-q1-s1");

        assertEquals(2, messages.size());
        assertEquals(RoleType.USER, messages.get(0).getRole());
        assertEquals("Remember ORCHID-42.", messages.get(0).getContent());
        assertEquals(RoleType.ASSISTANT, messages.get(1).getRole());
        assertEquals(
                "eval-longmemeval-q1-s1:1",
                messages.get(1).getOriginMessageKey());
    }

    @Test
    public void evaluatorSeedIdsStayStableAndBounded() {
        String requestId = EvalMemorySeedCli.seedRequestId(
                "nightly-01-abcdef",
                "eval-longmemeval-q1-s1");

        assertEquals("nightly-01-abcdef:eval-longmemeval-q1-s1", requestId);
        assertTrue(requestId.length() <= 64);
    }

    @Test
    public void longSeedRequestIdsStayWithinSessionColumnAndRemainStable() {
        String namespace = "eval-seed-" + "x".repeat(100);
        String sessionId = "eval-longmemeval-" + "y".repeat(100);

        String first = EvalMemorySeedCli.seedRequestId(namespace, sessionId);
        String second = EvalMemorySeedCli.seedRequestId(namespace, sessionId);

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.startsWith("eval-seed-"));
    }

    @Test
    public void rejectsNonEvalSessionIds() {
        JSONObject row = new JSONObject();
        row.put("role", "user");
        row.put("content", "remember this");
        JSONArray turns = new JSONArray();
        turns.add(row);
        JSONObject session = new JSONObject();
        session.put("sessionId", "production-session");
        session.put("timestamp", "2025-01-01 10:00:00");
        session.put("turns", turns);
        JSONArray sessions = new JSONArray();
        sessions.add(session);
        JSONObject request = new JSONObject();
        request.put("userId", "eval-user");
        request.put("sessions", sessions);

        assertThrows(
                IllegalArgumentException.class,
                () -> EvalMemorySeedCli.seed(request, null));
    }

    private static JSONObject turn(String role, String content) {
        JSONObject row = new JSONObject();
        row.put("role", role);
        row.put("content", content);
        return row;
    }
}
