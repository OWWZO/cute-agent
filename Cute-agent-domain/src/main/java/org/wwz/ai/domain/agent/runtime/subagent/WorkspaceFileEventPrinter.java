package org.wwz.ai.domain.agent.runtime.subagent;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.printer.Printer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Relays child workspace file metadata to the root session stream.
 *
 * <p>Only {@code file} events cross the sub-agent stream boundary. Thinking,
 * tool calls, and final replies stay in the isolated child session.</p>
 */
public final class WorkspaceFileEventPrinter implements Printer {

    public static final String FILE_MESSAGE_TYPE = "file";
    public static final String WORKSPACE_ONLY_KEY = "workspaceOnly";

    private final Printer childPrinter;
    private final Printer parentPrinter;
    private final Map<String, Object> workspaceIdentity;

    public WorkspaceFileEventPrinter(Printer childPrinter,
                                     Printer parentPrinter,
                                     String rootSessionId,
                                     String childSessionId,
                                     String childRequestId,
                                     String parentToolUseId,
                                     String subAgentId,
                                     String subAgentType) {
        this.childPrinter = childPrinter;
        this.parentPrinter = parentPrinter;
        this.workspaceIdentity = new LinkedHashMap<>();
        putIfNotBlank(workspaceIdentity, "rootSessionId", rootSessionId);
        putIfNotBlank(workspaceIdentity, "producerSessionId", childSessionId);
        putIfNotBlank(workspaceIdentity, "childSessionId", childSessionId);
        putIfNotBlank(workspaceIdentity, "childRequestId", childRequestId);
        putIfNotBlank(workspaceIdentity, "parentToolUseId", parentToolUseId);
        putIfNotBlank(workspaceIdentity, SubAgentPrinter.KEY_SUB_AGENT_ID, subAgentId);
        putIfNotBlank(workspaceIdentity, SubAgentPrinter.KEY_SUB_AGENT_TYPE, subAgentType);
        workspaceIdentity.put(WORKSPACE_ONLY_KEY, Boolean.TRUE);
    }

    public static Printer wrap(Printer childPrinter,
                               Printer parentPrinter,
                               String rootSessionId,
                               String childSessionId,
                               String childRequestId,
                               String parentToolUseId,
                               String subAgentId,
                               String subAgentType) {
        if (childPrinter == null || parentPrinter == null || childPrinter == parentPrinter) {
            return childPrinter;
        }
        return new WorkspaceFileEventPrinter(
                childPrinter,
                parentPrinter,
                rootSessionId,
                childSessionId,
                childRequestId,
                parentToolUseId,
                subAgentId,
                subAgentType
        );
    }

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
        if (childPrinter != null) {
            childPrinter.send(messageId, messageType, message, extraResultMap, digitalEmployee, isFinal);
        }
        if (!FILE_MESSAGE_TYPE.equals(messageType) || parentPrinter == null || !(message instanceof Map<?, ?> source)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                payload.put(String.valueOf(key), value);
            }
        });
        payload.putAll(workspaceIdentity);

        Map<String, Object> extra = extraResultMap == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(extraResultMap);
        extra.putAll(workspaceIdentity);
        parentPrinter.send(messageId, messageType, payload, extra, null, Boolean.TRUE);
    }

    @Override
    public void close() {
        // The isolated child stream is owned and closed by SubAgentRunner.
    }

    @Override
    public void updateAgentType(AgentType agentType) {
        if (childPrinter != null) {
            childPrinter.updateAgentType(agentType);
        }
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }
}
