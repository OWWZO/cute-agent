package org.wwz.ai.application.agent.stream;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class AgentStreamProjectionPruneKeyTest {

    @Test
    public void resolvesDeepSearchToolCallIdFromNestedResultMap() {
        Map<String, Object> stage = new HashMap<>();
        stage.put("messageType", "deep_search");
        stage.put("toolCallId", "call-deep");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("resultMap", stage);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("messageType", "task");
        eventData.put("resultMap", resultMap);

        Assert.assertEquals(
                "tool:call-deep",
                AgentStreamProjection.resolvePruneGroupKey(eventData)
        );
    }
}
