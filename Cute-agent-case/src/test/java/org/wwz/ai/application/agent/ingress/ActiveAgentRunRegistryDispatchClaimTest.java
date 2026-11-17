package org.wwz.ai.application.agent.ingress;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;

public class ActiveAgentRunRegistryDispatchClaimTest {

    @Test
    public void duplicateRequestOnlyOneCallerCanClaimDispatch() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();

        ActiveAgentRunRegistry.ActiveRun first = registry.begin("req-1", "sess-1", "user-1");
        ActiveAgentRunRegistry.ActiveRun second = registry.begin("req-1", "sess-1", "user-1");

        Assert.assertSame(first, second);
        Assert.assertTrue(first.tryClaimDispatch());
        Assert.assertFalse(second.tryClaimDispatch());
    }

    @Test(expected = AgentConcurrentRunException.class)
    public void sameRequestIdCannotBeReusedAcrossSessions() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        registry.begin("req-1", "sess-1", "user-1");

        registry.begin("req-1", "sess-2", "user-1");
    }
}
