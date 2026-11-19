package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputNames;
import org.wwz.ai.domain.agent.runtime.dto.DeepSearchResponse;
import org.wwz.ai.domain.agent.runtime.tool.common.DeepSearchStageEventMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * deep_search projector。
 */
/**
 * DeepSearch 工具的历史回放投影器，恢复搜索阶段、章节总结、查询结果和引用信息。
 */
public class DeepSearchToolInvocationProjector extends AbstractToolInvocationProjector {

    @Override
    public boolean supports(String toolName) {
        return ToolOutputNames.DEEP_SEARCH.equals(toolName);
    }

    @Override
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state) {
        DeepSearchToolOutput output = invocation != null && invocation.getStructuredOutput() instanceof DeepSearchToolOutput structuredOutput
                ? structuredOutput
                : null;
        if (output == null || output.getStages() == null || output.getStages().isEmpty()) {
            if (invocation != null
                    && Integer.valueOf(ExecutionLedgerConstants.STATUS_RUNNING).equals(invocation.getStatus())) {
                return projectRunningInvocation(invocation, artifacts, state);
            }
            return projectAsToolResult(invocation, artifacts, state);
        }

        List<ProjectedReplayEvent> events = new ArrayList<>();
        for (DeepSearchStage stage : output.getStages()) {
            String stageType = stage == null ? null : stage.getStage();
            if (StringUtils.isBlank(stageType)) {
                continue;
            }
            DeepSearchResponse response = DeepSearchStageEventMapper.toResponse(
                    output.getQuery(),
                    output.getChapters(),
                    stage
            );
            Map<String, Object> resultMap = DeepSearchStageEventMapper.toResultMap(response);
            if (resultMap.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> stageArtifactRefs = resolveStageArtifactRefs(stageType, artifacts);
            if (DeepSearchStage.STAGE_REPORT.equals(stageType) && !stageArtifactRefs.isEmpty()) {
                resultMap.put("fileInfo", stageArtifactRefs);
            }
            events.add(buildTaskEvent(
                    state,
                    invocation,
                    ToolOutputNames.DEEP_SEARCH,
                    buildStructuredToolResponse(invocation, ToolOutputNames.DEEP_SEARCH, resultMap),
                    stageArtifactRefs
            ));
        }
        return events;
    }

    /**
     * 运行中的工具还没有结构化输出，但刷新后仍需保留 DeepSearch 卡片。
     * 用模型入参生成一个 extend 占位阶段，后续实时/最终阶段会复用同一 toolCallId。
     */
    private List<ProjectedReplayEvent> projectRunningInvocation(ToolInvocationView invocation,
                                                                List<ArtifactView> artifacts,
                                                                AgentStreamAccumulator state) {
        Map<String, Object> input = readMap(invocation.getInputJson());
        String query = StringUtils.defaultIfBlank(
                stringValue(input.get("query")),
                "正在执行深度研究"
        );
        DeepSearchStage runningStage = DeepSearchStage.extend(List.of(query));
        DeepSearchResponse response = DeepSearchStageEventMapper.toResponse(
                query,
                List.of(),
                runningStage
        );
        Map<String, Object> resultMap = DeepSearchStageEventMapper.toResultMap(response);
        List<Map<String, Object>> artifactRefs = buildArtifactRefs(visibleArtifacts(artifacts));
        if (!artifactRefs.isEmpty()) {
            resultMap.put("fileInfo", artifactRefs);
        }
        return List.of(buildTaskEvent(
                state,
                invocation,
                ToolOutputNames.DEEP_SEARCH,
                buildStructuredToolResponse(
                        invocation,
                        ToolOutputNames.DEEP_SEARCH,
                        resultMap
                ),
                artifactRefs
        ));
    }

    private List<Map<String, Object>> resolveStageArtifactRefs(String stageType, List<ArtifactView> artifacts) {
        if (!DeepSearchStage.STAGE_REPORT.equals(stageType)) {
            return List.of();
        }
        return buildArtifactRefs(visibleArtifacts(artifacts));
    }

    private List<ArtifactView> visibleArtifacts(List<ArtifactView> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream()
                .filter(artifact -> artifact != null && StringUtils.isNotBlank(artifact.getFileName()))
                .filter(artifact -> !ExecutionLedgerConstants.VISIBILITY_INTERNAL.equalsIgnoreCase(
                        artifact.getVisibility()))
                .filter(artifact -> !StringUtils.endsWithIgnoreCase(artifact.getFileName(), "_search_result.txt"))
                .collect(Collectors.toList());
    }
}
