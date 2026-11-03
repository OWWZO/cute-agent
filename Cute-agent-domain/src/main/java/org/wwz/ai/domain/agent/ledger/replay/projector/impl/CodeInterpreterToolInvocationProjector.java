package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.CodeInterpreterToolOutput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * code_interpreter projector。
 */
/**
 * 代码解释器工具的历史回放投影器，恢复执行状态和生成文件引用。
 */
public class CodeInterpreterToolInvocationProjector extends AbstractToolInvocationProjector {

    @Override
    public boolean supports(String toolName) {
        return "code_interpreter".equals(toolName);
    }

    @Override
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              AgentStreamAccumulator state) {
        CodeInterpreterToolOutput output = invocation != null && invocation.getStructuredOutput() instanceof CodeInterpreterToolOutput structuredOutput
                ? structuredOutput
                : null;
        String codeOutput = output == null ? "" : StringUtils.defaultString(output.getCodeOutput());
        if (StringUtils.isBlank(codeOutput) && invocation != null) {
            codeOutput = fallbackCodeOutput(invocation);
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("isFinal", true);
        resultMap.put("codeOutput", codeOutput);
        // 实时 SSE 的 data 是累计正文（回退到 codeOutput），与 codeOutput 不是同一份内容。
        resultMap.put("data", StringUtils.defaultIfBlank(
                output == null ? null : output.getContent(), codeOutput));
        if (StringUtils.isNotBlank(output == null ? null : output.getContent())) {
            resultMap.put("content", output.getContent());
        }
        if (StringUtils.isNotBlank(output == null ? null : output.getCode())) {
            resultMap.put("code", output.getCode());
        }
        if (StringUtils.isNotBlank(output == null ? null : output.getExplain())) {
            resultMap.put("explain", output.getExplain());
        }
        resultMap.put("fileInfo", mergeFileRefs(output == null ? null : output.getFileRefs(), artifacts));

        return List.of(buildTaskEvent(
                state,
                invocation,
                "code",
                buildStructuredToolResponse(invocation, "code", resultMap),
                buildArtifactRefs(artifacts)
        ));
    }

    /**
     * 历史行没有结构化输出时，{@code resolveResultText()} 是整包工具结果 JSON；
     * 直接塞进 codeOutput 会把 JSON 当代码展示，因此优先抽取业务字段，失败再用 errorMsg。
     */
    private String fallbackCodeOutput(ToolInvocationView invocation) {
        if (invocation.getStatus() != null
                && invocation.getStatus() == ExecutionLedgerConstants.STATUS_FAILED
                && StringUtils.isNotBlank(invocation.getErrorMsg())) {
            return invocation.getErrorMsg();
        }
        String raw = invocation.resolveResultText();
        if (StringUtils.isBlank(raw)) {
            return StringUtils.defaultString(invocation.getErrorMsg());
        }
        Map<String, Object> payload = readMap(raw);
        String fromCodeOutput = stringValue(payload.get("codeOutput"));
        if (StringUtils.isNotBlank(fromCodeOutput)) {
            return fromCodeOutput;
        }
        String fromContent = stringValue(payload.get("content"));
        if (StringUtils.isNotBlank(fromContent)) {
            return fromContent;
        }
        return StringUtils.defaultIfBlank(invocation.getErrorMsg(), raw);
    }
}
