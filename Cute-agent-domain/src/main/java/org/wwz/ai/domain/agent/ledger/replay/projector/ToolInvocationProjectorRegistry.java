package org.wwz.ai.domain.agent.ledger.replay.projector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;

import java.util.List;

/**
 * 工具调用历史回放投影器注册表。
 * <p>按 tool_name 选择专用 projector，并在没有匹配实现时使用兜底 projector。
 * 多个 projector 同时匹配时仍按注册顺序取第一个，但会打 warn 日志暴露配置重叠。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ToolInvocationProjectorRegistry {

    private final List<ToolInvocationProjector> projectors;
    private final ToolInvocationProjector defaultProjector;

    /**
     * 孤儿工具（无关联 LLM）：每个 invocation 单独开组，避免无序串组。
     */
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state) {
        return project(invocation, artifacts, state, false);
    }

    /**
     * 历史回放分组：
     * 1. reuseCurrentTaskGroup=true：与 live SSE 一致，复用当前 taskId（同 LLM 轮次工具批量共用）；
     * 2. reuseCurrentTaskGroup=false：独立开组（孤儿工具默认路径）。
     * planning 工具由 projector 自行 renew 计划步骤，不受此开关影响。
     */
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state,
                                              boolean reuseCurrentTaskGroup) {
        if (!reuseCurrentTaskGroup && !supportsPlannerTaskGrouping(invocation)) {
            state.renewTaskId();
        }
        String toolName = invocation == null ? null : invocation.getToolName();
        ToolInvocationProjector matched = null;
        for (ToolInvocationProjector projector : projectors) {
            if (projector == null || !projector.supports(toolName)) {
                continue;
            }
            if (matched == null) {
                matched = projector;
                continue;
            }
            log.warn("多个 tool projector 同时匹配 toolName={}，使用 {}，忽略 {}",
                    toolName,
                    matched.getClass().getSimpleName(),
                    projector.getClass().getSimpleName());
        }
        return matched == null
                ? defaultProjector.project(invocation, artifacts, state)
                : matched.project(invocation, artifacts, state);
    }

    private boolean supportsPlannerTaskGrouping(ToolInvocationView invocation) {
        return invocation != null && "planning".equals(invocation.getToolName());
    }
}
