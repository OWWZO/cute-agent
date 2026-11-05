package org.wwz.ai.application.agent.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SubAgentSessionStreamPortAdapterTest {

    @Test
    public void closeByPrinterMustUnregisterTheProjection() throws Exception {
        TrackingRegistry registry = new TrackingRegistry();
        SubAgentSessionStreamPortAdapter adapter = adapter(registry);

        Printer printer = adapter.openChildSessionStream(
                "child-registry", "user-1", "req-registry", "query", AgentType.REACT.getValue());

        Assert.assertTrue(registry.hasLive("child-registry"));
        adapter.closeChildSessionStream("child-registry", printer);
        Assert.assertEquals(1, registry.unregisterCalls);
        Assert.assertFalse(registry.hasLive("child-registry"));
    }

    @Test
    public void fallbackCloseMustCompleteActiveObservers() throws Exception {
        SessionProjectionRegistry registry = new SessionProjectionRegistry();
        SubAgentSessionStreamPortAdapter adapter = adapter(registry);
        adapter.openChildSessionStream(
                "child-fallback", "user-1", "req-fallback", "query", AgentType.REACT.getValue());
        AgentStreamProjection projection = registry.listLive("child-fallback").get(0);
        RecordingStream observer = new RecordingStream();
        projection.rebindDownstream(observer);

        adapter.closeChildSessionStream("child-fallback", null);

        Assert.assertTrue("兜底关闭也必须给观察者发送结束信号", observer.completed);
        Assert.assertFalse(registry.hasLive("child-fallback"));
    }

    @Test
    public void childProjectionMustUseTheParentAgentType() throws Exception {
        AtomicBoolean planHandlerCalled = new AtomicBoolean(false);
        AgentStreamEventHandler planHandler = (request, response, eventResult) -> {
            planHandlerCalled.set(true);
            return AgentStreamResult.builder()
                    .status("success")
                    .complete(false)
                    .frameType("result")
                    .eventData(Map.of())
                    .build();
        };
        SubAgentSessionStreamPortAdapter adapter = adapter(
                new SessionProjectionRegistry(), Map.of(AgentType.PLAN_SOLVE, planHandler));

        Printer printer = adapter.openChildSessionStream(
                "child-plan", "user-1", "req-plan", "query", AgentType.PLAN_SOLVE.getValue());
        printer.send("msg-1", "tool_thought", "planning", null, false);

        Assert.assertTrue("子会话投影必须使用主 Agent 的 PLAN_SOLVE handler", planHandlerCalled.get());
    }

    private static SubAgentSessionStreamPortAdapter adapter(SessionProjectionRegistry registry)
            throws Exception {
        return adapter(registry, Map.of());
    }

    private static SubAgentSessionStreamPortAdapter adapter(
            SessionProjectionRegistry registry,
            Map<AgentType, AgentStreamEventHandler> handlerMap) throws Exception {
        SubAgentSessionStreamPortAdapter adapter = new SubAgentSessionStreamPortAdapter();
        setField(adapter, "sessionProjectionRegistry", registry);
        setField(adapter, "agentSessionEventBus", null);
        setField(adapter, "sessionEventClock", null);
        setField(adapter, "handlerMap", handlerMap);
        return adapter;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TrackingRegistry extends SessionProjectionRegistry {
        private int unregisterCalls;

        @Override
        public void unregister(String sessionId,
                               AgentStreamProjection stream,
                               Runnable onSessionIdle) {
            unregisterCalls++;
            super.unregister(sessionId, stream, onSessionIdle);
        }
    }

    private static final class RecordingStream implements AgentSessionStream {
        private boolean completed;

        @Override
        public void send(Object payload) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable throwable) {
            completed = true;
        }

        @Override
        public void onAbort(Runnable abortHandler) {
        }

        @Override
        public boolean isAborted() {
            return false;
        }
    }
}
