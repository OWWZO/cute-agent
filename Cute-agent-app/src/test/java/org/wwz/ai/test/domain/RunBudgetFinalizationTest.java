package org.wwz.ai.test.domain;

import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.budget.RunBudget;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RunBudgetFinalizationTest {

    @Test
    public void baseAgentReservesLastStepForFinalAnswer() {
        RunBudget budget = RunBudget.create(10_000, 60_000, 10, 3);
        ProbeAgent agent = new ProbeAgent();
        agent.setContext(AgentContext.builder()
                .requestId("req-budget-finalization")
                .sessionId("session-budget-finalization")
                .query("answer the question")
                .runBudget(budget)
                .build());
        agent.setMaxSteps(3);

        String result = agent.run("answer the question");

        assertEquals(2, agent.steps);
        assertEquals("step_reserve", agent.finalizationReason);
        assertEquals("FINAL_ANSWER: ok", result);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertTrue(budget.isFinalizationStarted());
        assertTrue(budget.isFinalizationSucceeded());
    }

    @Test
    public void defaultFinalizationNoopClosesRunWithoutFabricatingFailureAnswer() {
        RunBudget budget = RunBudget.create(10_000, 60_000, 10, 2);
        NoopAgent agent = new NoopAgent();
        agent.setContext(AgentContext.builder()
                .requestId("req-budget-noop")
                .sessionId("session-budget-noop")
                .query("answer")
                .runBudget(budget)
                .build());
        agent.setMaxSteps(2);

        String result = agent.run("answer");

        assertEquals("step 1", result);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertTrue(budget.isFinalizationStarted());
    }

    @Test
    public void parallelAgentContextSharesSameRunBudget() {
        RunBudget budget = RunBudget.create(10_000, 60_000, 10, 3);
        AgentContext parent = AgentContext.builder()
                .requestId("req-budget-share")
                .sessionId("session-budget-share")
                .build();
        parent.setRunBudget(budget);

        AgentContext child = parent.forkForParallelTask("parallel task");

        assertSame(budget, child.getRunBudget());
        assertSame(parent.getToolAccessPolicy(), child.getToolAccessPolicy());
    }

    @Test
    public void toolDispatchIsDeniedBeforeHardLimitSideEffects() {
        RunBudget budget = RunBudget.create(10_000, 60_000, 1, 3);
        ProbeAgent agent = new ProbeAgent();
        agent.setContext(AgentContext.builder()
                .requestId("req-budget-tool")
                .sessionId("session-budget-tool")
                .runBudget(budget)
                .build());
        CountingTool tool = new CountingTool();
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool);
        agent.setAvailableTools(tools);

        String first = agent.executeTool(toolCall("call-1"));
        String second = agent.executeTool(toolCall("call-2"));

        assertEquals("ok", first);
        assertTrue(second.contains("评测预算已耗尽"));
        assertEquals(1, tool.calls);
        assertEquals("tool_calls", budget.exceededReason());
    }

    private static ToolCall toolCall(String id) {
        return ToolCall.builder()
                .id(id)
                .type("function")
                .function(ToolCall.Function.builder()
                        .name("counting")
                        .arguments("{}")
                        .build())
                .build();
    }

    private static final class ProbeAgent extends BaseAgent {
        private int steps;
        private String finalizationReason;

        @Override
        public String step() {
            steps++;
            return "step " + steps;
        }

        @Override
        protected String finalizeAfterBudget(String reason) {
            finalizationReason = reason;
            setState(AgentState.FINISHED);
            return "FINAL_ANSWER: ok";
        }
    }

    private static final class NoopAgent extends BaseAgent {
        private int steps;

        @Override
        public String step() {
            steps++;
            return "step " + steps;
        }
    }

    private static final class CountingTool implements BaseTool {
        private int calls;

        @Override
        public String getName() {
            return "counting";
        }

        @Override
        public String getDescription() {
            return "counting test tool";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            calls++;
            return ToolResultPayload.text("ok");
        }
    }
}
