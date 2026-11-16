package org.wwz.ai.test.domain;

import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionRecord;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EvalLiveRunnerCliTest {

    @Test
    public void executionCommandCarriesIsolatedWorkspaceRoot() {
        AgentExecutionCommand command = EvalLiveRunnerCli.executionCommand(
                "request-1",
                "session-1",
                "query",
                "D:/eval-workspaces/case-1"
        );

        assertEquals("D:/eval-workspaces/case-1", command.getWorkspaceRoot());
    }

    @Test
    public void requestIdFitsSourceRequestColumn() {
        String requestId = EvalLiveRunnerCli.requestId(
                "long-experiment-prefix-",
                "0123456789abcdef0123456789abcdef"
        );

        assertTrue(requestId.length() <= 64);
    }

    @Test
    public void waitingQuestionTextIsUsableAsEvaluationOutput() {
        UserQuestionRecord record = UserQuestionRecord.builder()
                .questions(List.of(
                        Map.of("question", "Which files should I clean up?"),
                        Map.of("question", "Should I archive them?")
                ))
                .build();

        assertEquals(
                "Which files should I clean up?\nShould I archive them?",
                EvalLiveRunnerCli.pendingQuestionText(List.of(record))
        );
    }
}
