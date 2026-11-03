package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListPublisher;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;

import java.util.Map;

/**
 * 发布是 best-effort：SSE sink 失败不能把已提交的 Todo 变更报成工具失败。
 */
public class SessionTaskListPublisherTest {

    @Test
    public void failingPrinterDoesNotEscapePublish() {
        SessionTaskListStore store = new SessionTaskListStore("list");
        store.create("任务", "详情", null, null);
        AgentContext context = AgentContext.builder()
                .requestId("req-publish")
                .sessionId("sess-publish")
                .printer(new ThrowingPrinter())
                .sessionControl(new SessionControl().withSessionTaskList(store))
                .build();

        SessionTaskListPublisher.publish(context);

        Assert.assertEquals(1, store.list().size());
    }

    private static final class ThrowingPrinter implements Printer {
        @Override
        public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
            throw new IllegalStateException("sse disconnected");
        }

        @Override
        public void send(String messageId, String messageType, Object message,
                         Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
            throw new IllegalStateException("sse disconnected");
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(org.wwz.ai.domain.agent.runtime.enums.AgentType agentType) {
        }
    }
}
