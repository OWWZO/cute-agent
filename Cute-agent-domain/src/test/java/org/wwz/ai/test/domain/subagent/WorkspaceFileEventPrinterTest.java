package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.subagent.WorkspaceFileEventPrinter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorkspaceFileEventPrinterTest {

    @Test
    public void fileEventIsRelayedWithWorkspaceIdentity() {
        RecordingPrinter child = new RecordingPrinter();
        RecordingPrinter parent = new RecordingPrinter();
        Printer relay = WorkspaceFileEventPrinter.wrap(
                child, parent, "root-session", "sub_agent-1", "sub:agent-1:req",
                "parent-tool-1", "agent-1", "general-purpose");

        Map<String, Object> fileInfo = new LinkedHashMap<>();
        fileInfo.put("fileName", "data/result.csv");
        fileInfo.put("relativePath", "data/result.csv");
        fileInfo.put("ossUrl", "https://files.example/result.csv");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileInfo", List.of(fileInfo));
        relay.send("file", payload);

        Assert.assertEquals(1, child.events.size());
        Assert.assertEquals(1, parent.events.size());
        Map<?, ?> relayed = (Map<?, ?>) parent.events.get(0).message;
        Assert.assertEquals(Boolean.TRUE, relayed.get("workspaceOnly"));
        Assert.assertEquals("root-session", relayed.get("rootSessionId"));
        Assert.assertEquals("sub_agent-1", relayed.get("producerSessionId"));
        Assert.assertEquals("parent-tool-1", relayed.get("parentToolUseId"));
        Assert.assertEquals("agent-1", relayed.get("subAgentId"));
        Assert.assertEquals(Boolean.TRUE, parent.events.get(0).extra.get("workspaceOnly"));
    }

    @Test
    public void nonFileEventStaysInChildStream() {
        RecordingPrinter child = new RecordingPrinter();
        RecordingPrinter parent = new RecordingPrinter();
        Printer relay = WorkspaceFileEventPrinter.wrap(
                child, parent, "root-session", "sub_agent-1", "sub:agent-1:req",
                "parent-tool-1", "agent-1", "general-purpose");

        relay.send("tool_thought", "private child process");

        Assert.assertEquals(1, child.events.size());
        Assert.assertTrue(parent.events.isEmpty());
    }

    private static final class RecordingPrinter implements Printer {
        private final List<SendCall> events = new ArrayList<>();

        @Override
        public void send(String messageId,
                         String messageType,
                         Object message,
                         String digitalEmployee,
                         Boolean isFinal) {
            send(messageId, messageType, message, null, digitalEmployee, isFinal);
        }

        @Override
        public void send(String messageId,
                         String messageType,
                         Object message,
                         Map<String, Object> extraResultMap,
                         String digitalEmployee,
                         Boolean isFinal) {
            events.add(new SendCall(messageId, messageType, message, extraResultMap, isFinal));
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(AgentType agentType) {
        }
    }

    private record SendCall(String messageId,
                            String messageType,
                            Object message,
                            Map<String, Object> extra,
                            Boolean isFinal) {
    }
}
