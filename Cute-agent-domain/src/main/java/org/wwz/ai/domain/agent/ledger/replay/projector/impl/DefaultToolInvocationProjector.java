package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;

import java.util.List;

/**
 * 未注册专用 projector 的工具回放兜底实现，保证未知工具仍能生成基础事件。
 */
public class DefaultToolInvocationProjector extends AbstractToolInvocationProjector {

    @Override
    public boolean supports(String toolName) {
        return false;
    }

    @Override
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state) {
        // 历史行 tool_result 为 NULL，回落到 llm_oberserve（旧数据那里存的就是完整 JSON）。
        // 失败时两列都被 serializeFailure 填成「Error: xxx」，errorMsg 因 defaultIfBlank 永远轮不上；
        // 基类按状态分流，保证错误码（ERR-xxx）不被 Error 前缀文本顶掉。
        return projectAsToolResult(invocation, artifacts, state);
    }
}
