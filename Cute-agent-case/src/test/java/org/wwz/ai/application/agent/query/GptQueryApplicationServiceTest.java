package org.wwz.ai.application.agent.query;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;
import org.wwz.ai.domain.agent.runtime.prompt.PlanSolvePrompt;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

public class GptQueryApplicationServiceTest {

    @Test
    public void projectionSetupFailureReleasesActiveRun() throws Exception {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        GptQueryApplicationService service = service(registry, new SessionProjectionRegistry() {
            @Override
            public void register(String sessionId, org.wwz.ai.application.agent.stream.AgentStreamProjection stream) {
                throw new IllegalStateException("registry unavailable");
            }
        }, new AgentRunLaunchGate(0));
        RecordingWakePort wakePort = new RecordingWakePort();
        setField(service, "backgroundTaskWakePort", wakePort);
        AgentExecutionCommand request = request();
        registry.begin(request.getRequestId(), request.getSessionId(), request.getUserId());

        try {
            schedule(service, request);
            Assert.fail("投影登记失败必须向上抛出");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(e.getCause() instanceof IllegalStateException);
        }
        Assert.assertTrue(registry.find(request.getRequestId()).isEmpty());
        Assert.assertEquals(1, wakePort.settleCount);
        Assert.assertEquals("session-1", wakePort.sessionId);
        Assert.assertEquals("request-1", wakePort.requestId);
    }

    @Test
    public void deferredRuntimeFailureReleasesActiveRun() throws Exception {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        GptQueryApplicationService service =
                service(registry, new SessionProjectionRegistry(), new AgentRunLaunchGate(0));
        RecordingWakePort wakePort = new RecordingWakePort();
        setField(service, "backgroundTaskWakePort", wakePort);
        AgentExecutionCommand request = request();
        registry.begin(request.getRequestId(), request.getSessionId(), request.getUserId());

        schedule(service, request);

        Assert.assertTrue(registry.find(request.getRequestId()).isEmpty());
        Assert.assertEquals(1, wakePort.settleCount);
        Assert.assertEquals("session-1", wakePort.sessionId);
        Assert.assertEquals("request-1", wakePort.requestId);
    }

    @Test
    public void firstTurnBindsSelectedAgentAndSnapshotsNormalPrompt() throws Exception {
        GptQueryApplicationService service = new GptQueryApplicationService();
        SubAgentRegistry registry = registryWithDataAgent();
        setField(service, "subAgentRegistry", registry);
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .sessionId("session-normal")
                .collaborationMode("normal")
                .mainAgentKey("data-analysis")
                .build();
        DialogueSession session = DialogueSession.builder()
                .sessionId("session-normal")
                .collaborationMode("normal")
                .runCount(0)
                .build();

        bindPrompt(service, session, request);

        Assert.assertEquals("data-analysis", request.getMainAgentKey());
        Assert.assertEquals("normal", request.getCollaborationMode());
        Assert.assertEquals(AgentType.REACT.getValue(), request.getAgentType());
        Assert.assertTrue(request.getPromptSnapshot().contains("数据分析职责"));
        Assert.assertTrue(request.getPromptSnapshot().contains(
                AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
        Assert.assertFalse(request.getPromptSnapshot().contains(
                PlanSolvePrompt.ORCHESTRATION_MARKER));
    }

    @Test
    public void firstTurnBindsClusterCoordinatorWithWorkerCatalog() throws Exception {
        GptQueryApplicationService service = new GptQueryApplicationService();
        setField(service, "subAgentRegistry", registryWithDataAgent());
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .sessionId("session-cluster")
                .collaborationMode("cluster")
                .build();
        DialogueSession session = DialogueSession.builder()
                .sessionId("session-cluster")
                .collaborationMode("cluster")
                .runCount(0)
                .build();

        bindPrompt(service, session, request);

        Assert.assertNull(request.getMainAgentKey());
        Assert.assertEquals("cluster", request.getCollaborationMode());
        Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), request.getAgentType());
        Assert.assertTrue(request.getPromptSnapshot().contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
        Assert.assertTrue(request.getPromptSnapshot().contains("`data-analysis`: 数据分析"));
    }

    @Test
    public void subAgentSessionUsesItsOwnAgentIdentity() throws Exception {
        GptQueryApplicationService service = new GptQueryApplicationService();
        setField(service, "subAgentRegistry", registryWithDataAgent());
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .sessionId("session-child")
                .build();
        DialogueSession session = DialogueSession.builder()
                .sessionId("session-child")
                .sessionKind("subagent")
                .subAgentType("data-analysis")
                .collaborationMode("normal")
                .runCount(0)
                .build();

        bindPrompt(service, session, request);

        Assert.assertEquals("data-analysis", request.getMainAgentKey());
        Assert.assertEquals(AgentType.REACT.getValue(), request.getAgentType());
        Assert.assertTrue(request.getPromptSnapshot().contains("数据分析职责"));
        Assert.assertTrue(request.getPromptSnapshot().contains(
                AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
    }

    private static GptQueryApplicationService service(ActiveAgentRunRegistry registry,
                                                      SessionProjectionRegistry projections,
                                                      AgentRunLaunchGate gate) {
        GptQueryApplicationService service = new GptQueryApplicationService();
        setField(service, "handlerMap", Map.of());
        setField(service, "dispatchExecutor", null);
        setField(service, "activeAgentRunRegistry", registry);
        setField(service, "agentRunLaunchGate", gate);
        setField(service, "sessionProjectionRegistry", projections);
        return service;
    }

    private static void schedule(GptQueryApplicationService service, AgentExecutionCommand request) throws Exception {
        Method method = GptQueryApplicationService.class
                .getDeclaredMethod("scheduleDispatch", AgentExecutionCommand.class, String.class);
        method.setAccessible(true);
        method.invoke(service, request, request.getUserId());
    }

    private static AgentExecutionCommand request() {
        return AgentExecutionCommand.builder()
                .requestId("request-1")
                .sessionId("session-1")
                .userId("user-1")
                .query("hello")
                .isStream(true)
                .build();
    }

    private static void bindPrompt(GptQueryApplicationService service,
                                   DialogueSession session,
                                   AgentExecutionCommand request) throws Exception {
        Method method = GptQueryApplicationService.class.getDeclaredMethod(
                "applyPromptBinding", DialogueSession.class, AgentExecutionCommand.class);
        method.setAccessible(true);
        method.invoke(service, session, request);
    }

    private static SubAgentRegistry registryWithDataAgent() {
        SubAgentRegistry registry = new SubAgentRegistry();
        registry.register(SubAgentDefinition.builder()
                .agentType("data-analysis")
                .whenToUse("数据分析")
                .systemPrompt("数据分析职责")
                .allowedTools(Set.of("*"))
                .build());
        return registry;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingWakePort implements BackgroundTaskWakePort {
        private int settleCount;
        private String sessionId;
        private String requestId;

        @Override
        public boolean onBackgroundTaskCompleted(BackgroundTaskCompletionEvent event) {
            return false;
        }

        @Override
        public void onRunSettled(String sessionId) {
            settleCount++;
            this.sessionId = sessionId;
        }

        @Override
        public void onRunSettled(String sessionId, String requestId) {
            settleCount++;
            this.sessionId = sessionId;
            this.requestId = requestId;
        }
    }
}
