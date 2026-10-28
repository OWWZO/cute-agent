package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.query.ConversationSessionPreferencesApplicationService;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;

import java.time.LocalDateTime;

/**
 * 会话级偏好 upsert 语义：写入、COALESCE（未传不清空）、全 null 不落库、身份列不被改写。
 */
public class ConversationSessionPreferencesTest {

    @Test
    public void shouldPersistPreferencesOnExistingSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedConversation(ctx, "sess-pref-1");
        ConversationSessionPreferencesApplicationService service =
                new ConversationSessionPreferencesApplicationService(ctx.writeRepository);

        service.upsertPreferences("sess-pref-1", "gpt-5", Boolean.TRUE, "high", 1);

        DialogueSession row = ctx.readRepository.querySessionEntity("sess-pref-1");
        Assert.assertEquals("gpt-5", row.getModel());
        Assert.assertEquals(Boolean.TRUE, row.getThinking());
        Assert.assertEquals("high", row.getThinkingEffort());
        Assert.assertEquals(Integer.valueOf(1), row.getDeepThink());
    }

    @Test
    public void nullValuesMustNotClearPersistedPreferences() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedConversation(ctx, "sess-pref-2");
        ConversationSessionPreferencesApplicationService service =
                new ConversationSessionPreferencesApplicationService(ctx.writeRepository);

        service.upsertPreferences("sess-pref-2", "gpt-5", Boolean.TRUE, "high", 1);
        // 第二轮用户只换了模型，其余三个没传：库里原值必须保留
        service.upsertPreferences("sess-pref-2", "gpt-5-mini", null, null, null);

        DialogueSession row = ctx.readRepository.querySessionEntity("sess-pref-2");
        Assert.assertEquals("gpt-5-mini", row.getModel());
        Assert.assertEquals(Boolean.TRUE, row.getThinking());
        Assert.assertEquals("high", row.getThinkingEffort());
        Assert.assertEquals(Integer.valueOf(1), row.getDeepThink());
    }

    @Test
    public void allNullMustNotTouchRepository() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedConversation(ctx, "sess-pref-3");
        ConversationSessionPreferencesApplicationService service =
                new ConversationSessionPreferencesApplicationService(ctx.writeRepository);

        service.upsertPreferences("sess-pref-3", null, null, null, null);
        service.upsertPreferences("", "gpt-5", null, null, null);

        DialogueSession row = ctx.readRepository.querySessionEntity("sess-pref-3");
        Assert.assertNull("四个参数全 null 时不得写入任何偏好", row.getModel());
        Assert.assertNull(row.getThinking());
        Assert.assertNull(row.getThinkingEffort());
        // 建行时 SQL 默认 0，未显式传值时保持不变
        Assert.assertEquals(Integer.valueOf(0), row.getDeepThink());
    }

    @Test
    public void preferencesMustNotRewriteIdentityColumns() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        LocalDateTime now = LocalDateTime.now();
        ctx.writeRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                .sessionId("sub_pref-4")
                .userId("user-1")
                .sessionKind(SessionKind.SUBAGENT.code())
                .parentSessionId("parent-1")
                .rootSessionId("parent-1")
                .title("子任务")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .startedAt(now)
                .lastActiveAt(now)
                .build());
        ConversationSessionPreferencesApplicationService service =
                new ConversationSessionPreferencesApplicationService(ctx.writeRepository);

        service.upsertPreferences("sub_pref-4", "gpt-5", Boolean.FALSE, "low", 0);

        DialogueSession row = ctx.readRepository.querySessionEntity("sub_pref-4");
        Assert.assertEquals("gpt-5", row.getModel());
        Assert.assertEquals("身份列不得被偏好写入改写",
                SessionKind.SUBAGENT.code(), row.getSessionKind());
        Assert.assertEquals("parent-1", row.getParentSessionId());
        Assert.assertEquals("parent-1", row.getRootSessionId());
        Assert.assertEquals("子任务", row.getTitle());
    }

    private static void seedConversation(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                                         String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        ctx.writeRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                .sessionId(sessionId)
                .userId("user-1")
                .sessionKind(SessionKind.CONVERSATION.code())
                .rootSessionId(sessionId)
                .title("用户会话")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(now)
                .lastActiveAt(now)
                .build());
    }
}
