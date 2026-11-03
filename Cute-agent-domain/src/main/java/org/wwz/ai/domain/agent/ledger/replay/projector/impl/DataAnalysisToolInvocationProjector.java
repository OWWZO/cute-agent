package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DataAnalysisToolOutput;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据分析工具的历史回放投影器，恢复分析过程、结果摘要和产物信息。
 */
public class DataAnalysisToolInvocationProjector extends AbstractToolInvocationProjector {

    @Override
    public boolean supports(String toolName) {
        return ToolOutputNames.DATA_ANALYSIS.equals(toolName);
    }

    @Override
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state) {
        // failure() 也会产出 DataAnalysisToolOutput（content 为空、summary 是错误文案），
        // 只看 output != null 会把失败的分析回放成 isFinal=true 的成功结果。
        if (isFailedInvocation(invocation)) {
            return projectAsToolResult(invocation, artifacts, state);
        }
        DataAnalysisToolOutput output = invocation != null && invocation.getStructuredOutput() instanceof DataAnalysisToolOutput structuredOutput
                ? structuredOutput
                : null;
        String data = output == null ? "" : StringUtils.defaultString(output.getContent());
        if (StringUtils.isBlank(data) && invocation != null) {
            data = StringUtils.defaultString(invocation.resolveResultText());
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("isFinal", true);
        resultMap.put("task", output == null ? "" : StringUtils.defaultString(output.getTask()));
        resultMap.put("data", data);
        resultMap.put("fileInfo", mergeFileRefs(output == null ? null : output.getFileRefs(), artifacts));

        return List.of(buildTaskEvent(
                state,
                invocation,
                ToolOutputNames.DATA_ANALYSIS,
                buildStructuredToolResponse(invocation, ToolOutputNames.DATA_ANALYSIS, resultMap),
                buildArtifactRefs(artifacts)
        ));
    }
}
