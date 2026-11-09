package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.runtime.budget.RunBudget;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EvalBenchmarkBudgetTest {

    @Test
    public void budgetExceededCoversWallTokensAndToolCalls() {
        JSONObject casePayload = new JSONObject();
        casePayload.put("maxWallMs", 1000);
        casePayload.put("maxTotalTokens", 100);
        casePayload.put("maxToolCalls", 3);
        EvalBenchmarkLiveRunnerCli.BudgetLimits limits =
                EvalBenchmarkLiveRunnerCli.BudgetLimits.from(casePayload);

        assertFalse(EvalBenchmarkLiveRunnerCli.budgetExceeded(999, 100, 3, limits));
        assertTrue(EvalBenchmarkLiveRunnerCli.budgetExceeded(1001, 0, 0, limits));
        assertTrue(EvalBenchmarkLiveRunnerCli.budgetExceeded(0, 101, 0, limits));
        assertTrue(EvalBenchmarkLiveRunnerCli.budgetExceeded(0, 0, 4, limits));
    }

    @Test
    public void answerContractInstructionIsAppendedOutsideOfficialQuestion() {
        JSONObject contract = new JSONObject();
        contract.put("instruction", "FINAL ANSWER CONTRACT");

        assertEquals(
                "official question\n\nFINAL ANSWER CONTRACT",
                EvalBenchmarkLiveRunnerCli.promptWithContract("official question", contract)
        );
        assertEquals(
                "official question",
                EvalBenchmarkLiveRunnerCli.promptWithContract("official question", null)
        );
    }

    @Test
    public void blankFinalAnswerCannotBeMarkedAsSuccess() {
        assertNull(EvalBenchmarkLiveRunnerCli.finalAnswerError("FINAL_ANSWER: ok"));
        assertEquals(
                "agent finished without a final answer",
                EvalBenchmarkLiveRunnerCli.finalAnswerError("  ")
        );
        assertEquals(
                "agent finished without a final answer",
                EvalBenchmarkLiveRunnerCli.finalAnswerError(null)
        );
    }

    @Test
    public void traceTokenTotalIncludesEveryLlmInvocation() {
        ExecutionRunDetail detail = ExecutionRunDetail.builder()
                .llmInvocations(List.of(
                        LlmInvocationView.builder()
                                .promptTokens(6)
                                .completionTokens(4)
                                .totalTokens(10)
                                .build(),
                        LlmInvocationView.builder()
                                .promptTokens(5)
                                .completionTokens(2)
                                .totalTokens(7)
                                .build(),
                        LlmInvocationView.builder()
                                .promptTokens(null)
                                .completionTokens(null)
                                .totalTokens(null)
                                .build()
                ))
                .build();
        JSONObject trace = new JSONObject();
        JSONObject run = new JSONObject();
        run.put("promptTokens", 6);
        run.put("completionTokens", 4);
        run.put("totalTokens", 10);
        trace.put("run", run);

        EvalBenchmarkLiveRunnerCli.applyInvocationTotals(trace, detail);

        assertEquals(11, trace.getJSONObject("run").getIntValue("promptTokens"));
        assertEquals(6, trace.getJSONObject("run").getIntValue("completionTokens"));
        assertEquals(17, trace.getJSONObject("run").getIntValue("totalTokens"));
    }

    @Test
    public void schemaCacheVariantPreservesToolOrderAndSemantics() {
        JSONArray tools = new JSONArray();
        JSONObject alpha = new JSONObject();
        alpha.put("name", "alpha");
        alpha.put("description", "first");
        alpha.put("parameters", new JSONObject());
        tools.add(alpha);
        JSONObject beta = new JSONObject();
        beta.put("name", "beta");
        beta.put("description", "second");
        beta.put("parameters", new JSONObject());
        tools.add(beta);

        JSONArray stable = EvalBenchmarkLiveRunnerCli.variantTools(
                tools, "stable", "nonce");
        JSONArray changed = EvalBenchmarkLiveRunnerCli.variantTools(
                tools, "schema-change", "nonce");

        assertEquals("alpha", changed.getJSONObject(0).getString("name"));
        assertEquals("beta", changed.getJSONObject(1).getString("name"));
        assertEquals(
                "parameters",
                changed.getJSONObject(0).keySet().iterator().next()
        );
        assertEquals(
                stable.getJSONObject(0).getJSONObject("parameters").toJSONString(),
                changed.getJSONObject(0).getJSONObject("parameters").toJSONString()
        );
        assertEquals(
                stable.getJSONObject(1).getString("description"),
                changed.getJSONObject(1).getString("description")
        );
    }

    @Test
    public void runBudgetReservesFinalStepAndRejectsFurtherTools() {
        RunBudget budget = RunBudget.create(1000, 100_000, 10, 5);

        assertNull(budget.claimFinalization(3));
        assertTrue(budget.tryAcquireTool().allowed());
        budget.recordTokens(800);

        assertNotNull(budget.claimFinalization(4));
        assertNull(budget.claimFinalization(4));
        RunBudget.ToolDecision denied = budget.tryAcquireTool();
        assertFalse(denied.allowed());
        assertFalse(denied.hardLimit());
        assertEquals("token_reserve", denied.reason());
        assertNull(budget.exceededReason());
    }

    @Test
    public void everyAgentCanClaimOneFinalizationFromSharedBudget() {
        RunBudget budget = RunBudget.create(1000, 100_000, 10, 5);
        Object mainAgent = new Object();
        Object subAgent = new Object();
        budget.recordTokens(800);

        assertNotNull(budget.claimFinalization(mainAgent, 4));
        assertNull(budget.claimFinalization(mainAgent, 4));
        assertNotNull(budget.claimFinalization(subAgent, 4));

        budget.completeFinalization(mainAgent, true);
        assertFalse(budget.isFinalizationSucceeded());
        budget.completeFinalization(subAgent, true);
        assertTrue(budget.isFinalizationSucceeded());
    }

    @Test
    public void runBudgetMarksHardTokenAndToolLimits() {
        RunBudget tokenBudget = RunBudget.create(100, 100_000, 10, 5);
        tokenBudget.recordTokens(101);
        assertEquals("total_tokens", tokenBudget.exceededReason());

        RunBudget toolBudget = RunBudget.create(1000, 100_000, 2, 5);
        assertTrue(toolBudget.tryAcquireTool().allowed());
        assertTrue(toolBudget.tryAcquireTool().allowed());
        RunBudget.ToolDecision denied = toolBudget.tryAcquireTool();
        assertFalse(denied.allowed());
        assertTrue(denied.hardLimit());
        assertEquals("tool_calls", toolBudget.exceededReason());
    }
}
