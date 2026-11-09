package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.CompactionBudget;
import org.wwz.ai.domain.agent.memory.CompactionPrompt;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;

import java.util.List;

public class EvalCompactionCliTest {

    @Test
    public void messageProtocolRoundTripsToolCallsAndRoles() {
        JSONArray rows = JSON.parseArray("""
                [
                  {
                    "role": "assistant",
                    "content": "call",
                    "toolCalls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {"name": "lookup", "arguments": "{\\"id\\":1}"}
                      }
                    ]
                  },
                  {"role": "tool", "content": "result", "toolCallId": "call-1"}
                ]
                """);

        List<Message> messages = EvalCompactionCli.toMessages(rows);

        Assert.assertEquals(RoleType.ASSISTANT, messages.get(0).getRole());
        Assert.assertEquals("lookup", messages.get(0).getToolCalls().get(0).getFunction().getName());
        Assert.assertEquals(RoleType.TOOL, messages.get(1).getRole());
        Assert.assertEquals("call-1", messages.get(1).getToolCallId());
        Assert.assertEquals(rows.size(), EvalCompactionCli.toJson(messages).size());
    }

    @Test
    public void evalBudgetKeepsSmallTriggerWindowAndDisablesSideEffects() {
        JSONObject raw = JSON.parseObject("""
                {
                  "contextWindow": 2000,
                  "thresholdPercent": 0.75,
                  "keepMaxTokens": 300,
                  "auditStoreMessages": true,
                  "persistProjection": true
                }
                """);

        CompactionBudget budget = EvalCompactionCli.toBudget(raw);

        Assert.assertEquals(2000, budget.getContextWindow());
        Assert.assertEquals(1700, budget.threshold());
        Assert.assertEquals(300, budget.getKeepMaxTokens());
        Assert.assertFalse(budget.isPersistProjection());
        Assert.assertTrue(budget.isAuditEnabled());
        Assert.assertFalse(budget.isAuditStoreMessages());
        Assert.assertFalse(budget.isMidRunEnabled());
    }

    @Test
    public void compactSummaryHashInputComesFromHandoffMessage() {
        String handoff = CompactionPrompt.wrapSummaryForReinject(
                """
                        ## Goal
                        continue
                        ## Constraints & Preferences
                        no delete
                        ## Active State
                        report
                        """,
                false);
        List<Message> messages = List.of(
                Message.userMessage(handoff, null),
                Message.userMessage("继续", null));

        Assert.assertEquals(handoff, EvalCompactionCli.compactSummary(messages));
        Assert.assertEquals(
                """
                        ## Goal
                        continue
                        ## Constraints & Preferences
                        no delete
                        ## Active State
                        report""",
                EvalCompactionCli.checkpointBody(handoff));
        Assert.assertNull(EvalCompactionCli.compactSummary(List.of(
                Message.userMessage("ordinary", null))));
    }
}
