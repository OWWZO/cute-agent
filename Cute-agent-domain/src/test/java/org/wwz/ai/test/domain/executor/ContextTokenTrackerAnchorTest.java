package org.wwz.ai.test.domain.executor;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.ContextTokenTracker;
import org.wwz.ai.domain.agent.runtime.llm.PromptShape;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文缩短到锚点以下时，旧的 provider usage 基线必须失效。
 */
public class ContextTokenTrackerAnchorTest {

    @Test
    public void shrunkContextFallsBackToLocalEstimate() {
        TokenCounter counter = new TokenCounter();
        Message system = Message.systemMessage("sys", null);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userMessage("u1", null));
        messages.add(Message.assistantMessage("a1", null));
        messages.add(Message.userMessage("u2", null));
        messages.add(Message.assistantMessage("a2", null));

        PromptShape original = PromptShape.text(system, messages);
        ContextTokenTracker tracker = new ContextTokenTracker(counter);
        tracker.recordProviderUsage(usage(), messages.size(), counter.fingerprint(original));

        List<Message> shrunk = messages.subList(0, 2);
        PromptShape shrunkShape = PromptShape.text(system, shrunk);
        ContextTokenTracker.ContextTokenEstimate estimate = tracker.estimateCurrentContext(shrunkShape);

        Assert.assertEquals(ContextTokenTracker.SOURCE_LOCAL_ESTIMATE, estimate.getEstimateSource());
        Assert.assertEquals(counter.estimatePrompt(shrunkShape).getEstimatedTotalTokens(),
                estimate.getEstimatedTokens());
    }

    private static org.wwz.ai.domain.agent.runtime.llm.LlmUsageSnapshot usage() {
        return org.wwz.ai.domain.agent.runtime.llm.LlmUsageSnapshot.builder()
                .promptTokens(100)
                .completionTokens(20)
                .totalTokens(120)
                .build();
    }
}
