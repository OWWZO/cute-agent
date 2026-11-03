package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.CodeInterpreterToolInvocationProjector;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.List;
import java.util.Map;

/**
 * 历史行缺少结构化输出时，回放不能把整包 JSON 当作代码输出。
 */
public class CodeInterpreterToolInvocationProjectorTest {

    private final CodeInterpreterToolInvocationProjector projector = new CodeInterpreterToolInvocationProjector();

    @Test
    public void legacyJsonObservationIsParsedIntoCodeOutputAndData() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-1")
                .toolName("code_interpreter")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .llmObservation("{\"tool\":\"code_interpreter\",\"ok\":true,"
                        + "\"codeOutput\":\"print(1)\",\"content\":\"hello\"}")
                .build();

        Map<String, Object> resultMap = projectResultMap(invocation);

        Assert.assertEquals("print(1)", resultMap.get("codeOutput"));
        // 历史行没有 content 时，实时语义的 data 回退到 codeOutput。
        Assert.assertEquals("print(1)", resultMap.get("data"));
    }

    @Test
    public void failedLegacyInvocationFallsBackToErrorMsg() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-2")
                .toolName("code_interpreter")
                .status(ExecutionLedgerConstants.STATUS_FAILED)
                .errorMsg("sandbox crashed")
                .llmObservation("{\"type\":\"tool_error\",\"message\":\"sandbox crashed\"}")
                .build();

        Map<String, Object> resultMap = projectResultMap(invocation);

        Assert.assertEquals("sandbox crashed", resultMap.get("codeOutput"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> projectResultMap(ToolInvocationView invocation) {
        List<ProjectedReplayEvent> events = projector.project(invocation, List.of(), new AgentStreamAccumulator());
        Map<String, Object> response = (Map<String, Object>) events.get(0).getResultMap();
        return (Map<String, Object>) response.get("resultMap");
    }
}
