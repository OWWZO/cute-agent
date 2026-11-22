package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.authorization.SessionNotInteractiveException;
import org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingOwnershipPort;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.memory.ltm.LtmManager;

/**
 * 会话归属应用服务测试。
 */
public class ConversationSessionAuthorizationServiceTest {

    @SuppressWarnings("unchecked")
    private static ConversationSessionAuthorizationService newService(
            ExecutionLedgerFixtureFactory.LedgerTestContext ctx) {
        ObjectProvider<LtmManager> ltm = Mockito.mock(ObjectProvider.class);
        Mockito.when(ltm.getIfAvailable()).thenReturn(null);
        return new ConversationSessionAuthorizationService(
                ctx.readRepository,
                ctx.writeRepository,
                ltm
        );
    }

    @SuppressWarnings("unchecked")
    private static ConversationSessionAuthorizationService newService(
            IExecutionLedgerReadRepository readRepository,
            IExecutionLedgerWriteRepository writeRepository) {
        ObjectProvider<LtmManager> ltm = Mockito.mock(ObjectProvider.class);
        Mockito.when(ltm.getIfAvailable()).thenReturn(null);
        return new ConversationSessionAuthorizationService(
                readRepository,
                writeRepository,
                ltm
        );
    }

    @Test
    public void shouldBindSessionToFirstUser() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionAuthorizationService service = newService(ctx);

        DialogueSession session = service.ensureSessionAccessible("user-001", "session-001", "帮我总结项目结构");

        Assert.assertNotNull(session);
        Assert.assertEquals("user-001", session.getUserId());
        Assert.assertEquals("session-001", session.getSessionId());
        Assert.assertEquals("帮我总结项目结构", session.getTitle());
        Assert.assertEquals("user-001", ctx.readRepository.querySessionEntity("session-001").getUserId());
    }

    @Test
    public void shouldAllowRepeatedAccessForSameUser() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionAuthorizationService service = newService(ctx);
        service.ensureSessionAccessible("user-001", "session-001", "第一次进入");

        DialogueSession session = service.ensureSessionAccessible("user-001", "session-001", "再次进入");

        Assert.assertNotNull(session);
        Assert.assertEquals("user-001", session.getUserId());
        Assert.assertEquals("session-001", session.getSessionId());
    }

    @Test
    public void rebindingOwnerlessSessionKeepsIdentityColumns() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ctx.writeRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                .sessionId("session-orphan")
                .bindingId(12L)
                .sessionKind(SessionKind.SUBAGENT.code())
                .parentSessionId("parent-001")
                .rootSessionId("root-001")
                .title("待认领会话")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(now)
                .lastActiveAt(now)
                .build());
        ConversationSessionAuthorizationService service = newService(ctx);

        service.ensureSessionAccessible("user-001", "session-orphan", "继续");

        DialogueSession persisted = ctx.readRepository.querySessionEntity("session-orphan");
        Assert.assertEquals("user-001", persisted.getUserId());
        Assert.assertEquals(Long.valueOf(12L), persisted.getBindingId());
        Assert.assertEquals(SessionKind.SUBAGENT.code(), persisted.getSessionKind());
        Assert.assertEquals("parent-001", persisted.getParentSessionId());
        Assert.assertEquals("root-001", persisted.getRootSessionId());
    }

    @Test
    public void rebindingOwnerlessSessionPassesCompleteRowToUpsert() {
        IExecutionLedgerReadRepository readRepository =
                Mockito.mock(IExecutionLedgerReadRepository.class);
        IExecutionLedgerWriteRepository writeRepository =
                Mockito.mock(IExecutionLedgerWriteRepository.class);
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.of(2026, 11, 4, 10, 0);
        java.time.LocalDateTime lastActiveAt = java.time.LocalDateTime.of(2026, 11, 4, 10, 5);
        DialogueSession existing = DialogueSession.builder()
                .sessionId("session-full")
                .bindingId(12L)
                .channelChatType("GROUP")
                .channelTargetId("group-001")
                .channelLastMsgId("inbound-msg-1")
                .channelLastReceivedAt(java.time.LocalDateTime.of(2026, 11, 12, 2, 0))
                .sessionKind(SessionKind.SUBAGENT.code())
                .parentSessionId("parent-001")
                .rootSessionId("root-001")
                .subAgentType("researcher")
                .title("待认领会话")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .latestRequestId("request-001")
                .latestQueryText("旧问题")
                .latestSummaryText("旧摘要")
                .runCount(3)
                .finishedRunCount(2)
                .failedRunCount(1)
                .startedAt(startedAt)
                .lastActiveAt(lastActiveAt)
                .model("gpt-test")
                .thinking(true)
                .thinkingEffort("high")
                .collaborationMode("cluster")
                .build();
        Mockito.when(readRepository.querySessionEntity("session-full")).thenReturn(existing);
        ConversationSessionAuthorizationService service =
                newService(readRepository, writeRepository);

        service.ensureSessionAccessible("user-001", "session-full", "继续");

        ArgumentCaptor<DialogueSessionUpsertRecord> captor =
                ArgumentCaptor.forClass(DialogueSessionUpsertRecord.class);
        Mockito.verify(writeRepository).upsertSession(captor.capture());
        DialogueSessionUpsertRecord record = captor.getValue();
        Assert.assertEquals(Long.valueOf(12L), record.getBindingId());
        Assert.assertEquals("GROUP", record.getChannelChatType());
        Assert.assertEquals("group-001", record.getChannelTargetId());
        Assert.assertEquals("inbound-msg-1", record.getChannelLastMsgId());
        Assert.assertEquals(java.time.LocalDateTime.of(2026, 11, 12, 2, 0),
                record.getChannelLastReceivedAt());
        Assert.assertEquals(SessionKind.SUBAGENT.code(), record.getSessionKind());
        Assert.assertEquals("parent-001", record.getParentSessionId());
        Assert.assertEquals("root-001", record.getRootSessionId());
        Assert.assertEquals("researcher", record.getSubAgentType());
        Assert.assertEquals("gpt-test", record.getModel());
        Assert.assertEquals(Boolean.TRUE, record.getThinking());
        Assert.assertEquals("high", record.getThinkingEffort());
        Assert.assertEquals(Integer.valueOf(1), record.getCollaborationMode());
    }

    @Test(expected = SessionOwnershipDeniedException.class)
    public void shouldRejectCrossUserAccess() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionAuthorizationService service = newService(ctx);
        service.ensureSessionAccessible("user-001", "session-001", "第一次进入");

        service.ensureSessionAccessible("user-002", "session-001", "尝试越权访问");
    }

    @Test
    public void shouldRejectMissingSessionWithExplicitMessage() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionAuthorizationService service = newService(ctx);

        try {
            service.ensureExistingSessionAccessible("user-001", "session-missing-001");
            Assert.fail("缺失会话应被拒绝");
        } catch (SessionOwnershipDeniedException exception) {
            Assert.assertEquals("当前会话不存在", exception.getMessage());
        }
    }

    @Test
    public void shouldAllowOwnerToReadSubAgentSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedSubAgentSession(ctx, "user-001", "sub_agent-1", "parent-001");
        ConversationSessionAuthorizationService service = newService(ctx);

        DialogueSession session = service.ensureExistingSessionAccessible("user-001", "sub_agent-1");

        Assert.assertNotNull("owner 应能读取子会话 history", session);
        Assert.assertEquals("user-001", session.getUserId());
        // 读取路径返回最小归属投影，但必须带上身份列，否则只读闸门无从判定
        Assert.assertEquals(SessionKind.SUBAGENT.code(), session.getSessionKind());
        // parent_session_id 属深列，读取路径不取；用全量查询单独校验
        Assert.assertEquals("parent-001",
                ctx.readRepository.querySessionEntity("sub_agent-1").getParentSessionId());
    }

    @Test
    public void shouldRejectCrossUserReadOfSubAgentSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedSubAgentSession(ctx, "user-001", "sub_agent-2", "parent-001");
        ConversationSessionAuthorizationService service = newService(ctx);

        try {
            service.ensureExistingSessionAccessible("user-002", "sub_agent-2");
            Assert.fail("跨用户读取子会话应被拒绝");
        } catch (SessionOwnershipDeniedException expected) {
            Assert.assertEquals("当前用户无权访问该会话", expected.getMessage());
        }
    }

    @Test
    public void shouldAllowSubmittingQueryOnSubAgentSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedSubAgentSession(ctx, "user-001", "sub_agent-3", "parent-001");
        ConversationSessionAuthorizationService service = newService(ctx);

        DialogueSession session =
                service.ensureSessionAccessible("user-001", "sub_agent-3", "继续这个子会话");

        Assert.assertNotNull("子会话与主会话同构，用户应能继续对话", session);
        Assert.assertEquals(SessionKind.SUBAGENT.code(), session.getSessionKind());
    }

    @Test
    public void shouldAllowResumeOnSubAgentSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedSubAgentSession(ctx, "user-001", "sub_agent-4", "parent-001");
        ConversationSessionAuthorizationService service = newService(ctx);

        DialogueSession session = service.ensureInteractiveSessionAccessible("user-001", "sub_agent-4");

        Assert.assertNotNull("子会话应可被 resume", session);
        Assert.assertEquals(SessionKind.SUBAGENT.code(), session.getSessionKind());
    }

    @Test
    public void shouldAllowInteractiveAccessForConversationSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionAuthorizationService service = newService(ctx);
        service.ensureSessionAccessible("user-001", "session-conv-1", "第一次进入");

        DialogueSession session = service.ensureInteractiveSessionAccessible("user-001", "session-conv-1");

        Assert.assertNotNull(session);
        Assert.assertEquals(SessionKind.CONVERSATION.code(), session.getSessionKind());
    }

    @Test
    public void shouldAllowChannelOwnerToContinueSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedChannelSession(ctx, "ch_12_hash", "qq_c2c_target", 12L);
        ConversationSessionAuthorizationService service = newService(ctx);
        bindChannelOwnership(service, 12L, "owner-001");

        DialogueSession submitted = service.ensureSessionAccessible("owner-001", "qq_c2c_target", "继续对话");
        DialogueSession resumed = service.ensureInteractiveSessionAccessible("owner-001", "qq_c2c_target");

        Assert.assertEquals("ch_12_hash", submitted.getUserId());
        Assert.assertEquals(Long.valueOf(12L), submitted.getBindingId());
        Assert.assertNotNull("owner 应能续跑渠道会话", resumed);
    }

    @Test
    public void shouldRejectNonOwnerOnChannelSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedChannelSession(ctx, "ch_12_hash", "qq_c2c_target", 12L);
        ConversationSessionAuthorizationService service = newService(ctx);
        bindChannelOwnership(service, 12L, "owner-001");

        try {
            service.ensureSessionAccessible("intruder", "qq_c2c_target", "越权续写");
            Assert.fail("非 owner 不应能续写渠道会话");
        } catch (SessionOwnershipDeniedException expected) {
            Assert.assertEquals("当前用户无权访问该会话", expected.getMessage());
        }
    }

    @Test
    public void shouldResolveRunUserIdForChannelOwner() {
        ConversationSessionAuthorizationService service =
                newService(ExecutionLedgerFixtureFactory.newLedgerTestContext());
        bindChannelOwnership(service, 12L, "owner-001");

        DialogueSession channelSession = DialogueSession.builder()
                .sessionId("qq_c2c_target")
                .userId("ch_12_hash")
                .bindingId(12L)
                .build();
        DialogueSession webSession = DialogueSession.builder()
                .sessionId("session-001")
                .userId("user-001")
                .build();

        Assert.assertEquals("ch_12_hash", service.resolveRunUserId("owner-001", channelSession));
        Assert.assertEquals("ch_12_hash", service.resolveRunUserId("ch_12_hash", channelSession));
        Assert.assertEquals("user-001", service.resolveRunUserId("user-001", webSession));
        Assert.assertEquals("intruder", service.resolveRunUserId("intruder", channelSession));
    }

    @SuppressWarnings("unchecked")
    private static void bindChannelOwnership(ConversationSessionAuthorizationService service,
                                             long bindingId,
                                             String ownerUserId) {
        ChannelBindingOwnershipPort port = (id, userId) ->
                id != null && id == bindingId && ownerUserId.equals(userId);
        ObjectProvider<ChannelBindingOwnershipPort> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable(Mockito.any())).thenReturn(port);
        ReflectionTestUtils.setField(service, "channelBindingOwnershipPortProvider", provider);
    }

    private static void seedChannelSession(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                                           String userId,
                                           String sessionId,
                                           Long bindingId) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ctx.writeRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                .sessionId(sessionId)
                .userId(userId)
                .bindingId(bindingId)
                .title("QQ 会话")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(now)
                .lastActiveAt(now)
                .build());
    }

    private static void seedSubAgentSession(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                                            String userId,
                                            String sessionId,
                                            String parentSessionId) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ctx.writeRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                .sessionId(sessionId)
                .userId(userId)
                .sessionKind(SessionKind.SUBAGENT.code())
                .parentSessionId(parentSessionId)
                .title("子任务")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(now)
                .lastActiveAt(now)
                .build());
    }
}
