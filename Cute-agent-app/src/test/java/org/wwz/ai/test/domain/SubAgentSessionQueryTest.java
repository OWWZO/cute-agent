package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.query.AgentQuerySubmitResult;
import org.wwz.ai.application.agent.query.ConversationSessionPreferencesApplicationService;
import org.wwz.ai.application.agent.query.GptQueryApplicationService;
import org.wwz.ai.application.agent.query.SessionPreferences;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.SessionEventClock;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelCatalog;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.Collections;

/**
 * 子会话独立 run 入口（模式 2）：从会话元数据重建运行时选择。
 */
public class SubAgentSessionQueryTest {

    @Test
    public void deepThinkDerivesPlanSolveAgentType() {
        Captured captured = runQuery(sessionWithDeepThink(1));
        Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), captured.command.getAgentType());
    }

    @Test
    public void reactWhenDeepThinkOff() {
        Captured captured = runQuery(sessionWithDeepThink(0));
        Assert.assertEquals(AgentType.REACT.getValue(), captured.command.getAgentType());
    }

    @Test
    public void nullMetadataStaysNullInsteadOfFakingDefaults() {
        DialogueSession session = baseSession();
        // model / thinking / thinkingEffort 都未显式选择过
        Captured captured = runQuery(session);

        Assert.assertNull("不得伪造默认模型，下游 AgentBootstrap 有回退逻辑",
                captured.command.getModel());
        Assert.assertNull(captured.command.getThinking());
        Assert.assertNull(captured.command.getThinkingEffort());
    }

    @Test
    public void metadataIsRestoredFromSessionRow() {
        DialogueSession session = baseSession();
        session.setModel("gpt-5");
        session.setThinking(Boolean.TRUE);
        session.setThinkingEffort("high");
        Captured captured = runQuery(session);

        Assert.assertEquals("gpt-5", captured.command.getModel());
        Assert.assertEquals(Boolean.TRUE, captured.command.getThinking());
        Assert.assertEquals("high", captured.command.getThinkingEffort());
    }

    @Test
    public void queryRunsOnSameChildSession() {
        Captured captured = runQuery(sessionWithDeepThink(0));
        Assert.assertEquals("sub_agent-1", captured.command.getSessionId());
        Assert.assertEquals("继续这个子会话", captured.command.getQuery());
    }

    @Test
    public void explicitPreferenceOverridesStoredValueAndIsPersisted() {
        DialogueSession session = baseSession();
        session.setModel("gpt-5");
        session.setDeepThink(0);
        Captured captured = runQuery(session, SessionPreferences.builder()
                .model("gpt-6")
                .deepThink(1)
                .build());

        // 运行时用本轮选择
        Assert.assertEquals("gpt-6", captured.command.getModel());
        Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), captured.command.getAgentType());
        // 落库只写用户真正选过的两列，其余为 null 由 COALESCE 保留
        Mockito.verify(captured.preferencesService).upsertPreferences(
                "sub_agent-1", "gpt-6", null, null, 1);
    }

    @Test
    public void missingPreferenceFallsBackToStoredValueWithoutPersisting() {
        DialogueSession session = baseSession();
        session.setModel("gpt-5");
        session.setThinkingEffort("high");
        Captured captured = runQuery(session, null);

        Assert.assertEquals("gpt-5", captured.command.getModel());
        Assert.assertEquals("high", captured.command.getThinkingEffort());
        // 本轮没有显式选择：不产生任何偏好写入
        Mockito.verifyNoInteractions(captured.preferencesService);
    }

    @Test(expected = SessionOwnershipDeniedException.class)
    public void missingSessionRejected() {
        GptQueryApplicationService service = newService();
        IExecutionLedgerReadRepository ledger = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(ledger.querySessionEntity(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "executionLedgerReadRepository", ledger);

        UserRequestContext.bind("user-1");
        try {
            service.submitSessionQuery("sub_missing", "hi", null);
        } finally {
            UserRequestContext.clear();
        }
    }

    private static Captured runQuery(DialogueSession session) {
        return runQuery(session, null);
    }

    private static Captured runQuery(DialogueSession session, SessionPreferences preferences) {
        GptQueryApplicationService service = newService();
        IExecutionLedgerReadRepository ledger = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(ledger.querySessionEntity(session.getSessionId())).thenReturn(session);
        ReflectionTestUtils.setField(service, "executionLedgerReadRepository", ledger);

        IAgentDispatchService dispatch = (IAgentDispatchService) ReflectionTestUtils.getField(
                service, "agentDispatchService");
        UserRequestContext.bind("user-1");
        AgentQuerySubmitResult result;
        try {
            result = service.submitSessionQuery(session.getSessionId(), "继续这个子会话", preferences);
        } finally {
            UserRequestContext.clear();
        }
        Assert.assertTrue(result.isAccepted());

        ArgumentCaptor<AgentExecutionCommand> captor = ArgumentCaptor.forClass(AgentExecutionCommand.class);
        try {
            Mockito.verify(dispatch).dispatch(captor.capture(), Mockito.any());
        } catch (Exception e) {
            throw new IllegalStateException("dispatch 校验失败", e);
        }
        return new Captured(captor.getValue(),
                (ConversationSessionPreferencesApplicationService) ReflectionTestUtils.getField(
                        service, "conversationSessionPreferencesApplicationService"));
    }

    private static DialogueSession sessionWithDeepThink(int deepThink) {
        DialogueSession session = baseSession();
        session.setDeepThink(deepThink);
        return session;
    }

    private static DialogueSession baseSession() {
        return DialogueSession.builder()
                .sessionId("sub_agent-1")
                .sessionKind(SessionKind.SUBAGENT.code())
                .parentSessionId("session-parent")
                .rootSessionId("session-parent")
                .deepThink(0)
                .build();
    }

    private static GptQueryApplicationService newService() {
        GptQueryApplicationService service = new GptQueryApplicationService();
        // 模型目录：库里存的模型视为仍可选用（目录下架的场景不在本测试范围）
        LlmModelCatalog catalog = Mockito.mock(LlmModelCatalog.class);
        Mockito.when(catalog.isUserSelectableModel(Mockito.anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "llmModelCatalog", catalog);
        ReflectionTestUtils.setField(service, "conversationSessionPreferencesApplicationService",
                Mockito.mock(ConversationSessionPreferencesApplicationService.class));
        ReflectionTestUtils.setField(service, "conversationSessionAuthorizationService",
                Mockito.mock(ConversationSessionAuthorizationService.class));
        ReflectionTestUtils.setField(service, "agentDispatchService",
                Mockito.mock(IAgentDispatchService.class));
        ReflectionTestUtils.setField(service, "handlerMap",
                Collections.<AgentType, AgentStreamEventHandler>emptyMap());
        ReflectionTestUtils.setField(service, "dispatchExecutor", (java.util.concurrent.Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "agentRunLaunchGate", new AgentRunLaunchGate(0));
        ReflectionTestUtils.setField(service, "agentSessionEventBus", Mockito.mock(AgentSessionEventBus.class));
        ReflectionTestUtils.setField(service, "sessionEventClock", Mockito.mock(SessionEventClock.class));

        ActiveAgentRunRegistry registry = Mockito.mock(ActiveAgentRunRegistry.class);
        Mockito.when(registry.begin(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> new ActiveAgentRunRegistry.ActiveRun(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        new RunCancellation()));
        ReflectionTestUtils.setField(service, "activeAgentRunRegistry", registry);
        return service;
    }

    private static final class Captured {
        private final AgentExecutionCommand command;
        private final ConversationSessionPreferencesApplicationService preferencesService;

        private Captured(AgentExecutionCommand command,
                         ConversationSessionPreferencesApplicationService preferencesService) {
            this.command = command;
            this.preferencesService = preferencesService;
        }
    }
}
