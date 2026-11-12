package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.replay.projector.support.PersistedYieldLookup;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlObservationSupport;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlStatuses;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.IDesktopControlRepository;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.RequestDesktopControlTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RequestDesktopControlToolInvocationProjector extends AbstractToolInvocationProjector {

    private final IDesktopControlRepository desktopControlRepository;

    public RequestDesktopControlToolInvocationProjector() {
        this(null);
    }

    public RequestDesktopControlToolInvocationProjector(IDesktopControlRepository desktopControlRepository) {
        this.desktopControlRepository = desktopControlRepository;
    }

    @Override
    public boolean supports(String toolName) {
        return StringUtils.equalsIgnoreCase(RequestDesktopControlTool.NAME, toolName);
    }

    @Override
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state) {
        Map<String, Object> input = readMap(invocation == null ? null : invocation.getInputJson());
        Map<String, Object> observation = readMap(invocation == null ? null : invocation.resolveResultText());
        String controlId = stringValue(observation.get("controlId"));
        DesktopControlRecord persisted = findPersisted(controlId, invocation, state);
        Map<String, Object> payload = persisted == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(DesktopControlObservationSupport.toReplayPayload(persisted));
        payload.put("messageType", "desktop_control");
        String resolvedControlId = StringUtils.defaultIfBlank(
                controlId,
                persisted == null ? null : persisted.getControlId()
        );

        String reason = persisted == null ? null : persisted.getReason();
        if (StringUtils.isBlank(reason)) {
            reason = stringValue(observation.get("reason"));
        }
        if (StringUtils.isBlank(reason)) {
            reason = stringValue(input.get("reason"));
        }
        if (StringUtils.isNotBlank(reason)) {
            payload.put("reason", reason);
        } else {
            payload.remove("reason");
        }
        payload.put("input", input);

        String status = persisted == null
                ? resolveFallbackStatus(observation, invocation)
                : DesktopControlObservationSupport.toClientStatus(persisted.getStatus());
        payload.put("status", status);
        if (persisted != null && StringUtils.isNotBlank(persisted.getStatus())) {
            payload.put("persistenceStatus", persisted.getStatus());
        }
        if (StringUtils.isNotBlank(resolvedControlId)) {
            payload.put("controlId", resolvedControlId);
        }

        return List.of(buildTaskEvent(
                state,
                invocation,
                "desktop_control",
                buildStructuredToolResponse(invocation, "desktop_control", payload),
                buildArtifactRefs(artifacts)
        ));
    }

    /**
     * 无持久化记录时按 observation / 账本状态还原客户端状态。
     * <p>失败/超时/停止不能被折叠成 pending，否则回放会显示一个永远不会结束的桌面控制卡片。</p>
     */
    private String resolveFallbackStatus(Map<String, Object> observation, ToolInvocationView invocation) {
        String observationStatus = stringValue(observation.get("status"));
        if (DesktopControlStatuses.COMPLETED.equalsIgnoreCase(observationStatus)) {
            return "completed";
        }
        if (isFailureObservationStatus(observationStatus) || isTerminalFailure(invocation)) {
            return "cancelled";
        }
        return "pending";
    }

    private boolean isFailureObservationStatus(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "failed", "failure", "error", "cancelled", "canceled", "timeout", "stopped" -> true;
            default -> false;
        };
    }

    private boolean isTerminalFailure(ToolInvocationView invocation) {
        if (invocation == null || invocation.getStatus() == null) {
            return false;
        }
        int status = invocation.getStatus();
        return status == ExecutionLedgerConstants.STATUS_FAILED
                || status == ExecutionLedgerConstants.STATUS_TIMEOUT
                || status == ExecutionLedgerConstants.STATUS_STOPPED;
    }

    private DesktopControlRecord findPersisted(String controlId,
                                               ToolInvocationView invocation,
                                               AgentStreamAccumulator state) {
        if (desktopControlRepository == null) {
            return null;
        }
        return PersistedYieldLookup.find(
                controlId,
                invocation,
                id -> desktopControlRepository.findByControlId(id).orElse(null),
                desktopControlRepository::listOpenBySessionId,
                DesktopControlRecord::getToolInvocationId,
                DesktopControlRecord::getToolCallId,
                state
        );
    }
}
