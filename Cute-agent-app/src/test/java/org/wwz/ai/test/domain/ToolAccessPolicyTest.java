package org.wwz.ai.test.domain;

import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.access.ToolAccessPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolAccessPolicyTest {

    @Test
    public void sameWebFetchUrlIsLimitedToTwoAttempts() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        assertTrue(policy.beforeWebFetch("https://example.com/a#top").allowed());
        assertTrue(policy.beforeWebFetch("https://example.com/a").allowed());
        ToolAccessPolicy.WebFetchDecision denied =
                policy.beforeWebFetch("https://example.com/a");

        assertFalse(denied.allowed());
        assertEquals("url_attempt_limit", denied.reason());
    }

    @Test
    public void consecutiveNetworkFailuresOpenCircuitForTheRun() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        assertTrue(policy.beforeWebFetch("https://example.com/a").allowed());
        policy.recordWebFetchFailure(true);
        assertFalse(policy.isWebFetchCircuitOpen());

        assertTrue(policy.beforeWebFetch("https://example.com/b").allowed());
        policy.recordWebFetchFailure(true);
        assertTrue(policy.isWebFetchCircuitOpen());

        ToolAccessPolicy.WebFetchDecision denied =
                policy.beforeWebFetch("https://example.com/c");
        assertFalse(denied.allowed());
        assertEquals("network_circuit_open", denied.reason());
    }

    @Test
    public void successfulFetchResetsConsecutiveNetworkFailureCount() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        policy.recordWebFetchFailure(true);
        policy.recordWebFetchSuccess();
        policy.recordWebFetchFailure(true);

        assertFalse(policy.isWebFetchCircuitOpen());
        assertEquals(1, policy.getConsecutiveNetworkFailures());
    }

    @Test
    public void successfulFetchClosesAnOpenCircuit() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        policy.recordWebFetchFailure(true);
        policy.recordWebFetchFailure(true);
        assertTrue(policy.isWebFetchCircuitOpen());

        policy.recordWebFetchSuccess();

        assertFalse(policy.isWebFetchCircuitOpen());
        assertEquals(0, policy.getConsecutiveNetworkFailures());
    }

    @Test
    public void nonNetworkFailureBreaksTheConsecutiveFailureChain() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        policy.recordWebFetchFailure(true);
        policy.recordWebFetchFailure(false);
        policy.recordWebFetchFailure(true);

        assertFalse(policy.isWebFetchCircuitOpen());
        assertEquals(1, policy.getConsecutiveNetworkFailures());
    }

    @Test
    public void circuitOpenKeepsTheAttemptCountForTheSameUrl() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        assertTrue(policy.beforeWebFetch("https://example.com/a").allowed());
        policy.recordWebFetchFailure(true);
        assertTrue(policy.beforeWebFetch("https://example.com/a").allowed());
        policy.recordWebFetchFailure(true);

        ToolAccessPolicy.WebFetchDecision denied =
                policy.beforeWebFetch("https://example.com/a");

        assertFalse(denied.allowed());
        assertEquals("network_circuit_open", denied.reason());
        assertEquals(2, denied.attempt());
    }

    @Test
    public void differentPortsUseIndependentAttemptBuckets() {
        ToolAccessPolicy policy = new ToolAccessPolicy();

        assertTrue(policy.beforeWebFetch("http://example.com:8080/doc").allowed());
        assertTrue(policy.beforeWebFetch("http://example.com:8080/doc").allowed());
        assertFalse(policy.beforeWebFetch("http://example.com:8080/doc").allowed());
        assertTrue(policy.beforeWebFetch("http://example.com:9090/doc").allowed());
    }

    @Test
    public void webFetchCircuitHintPointsToLocalWorkspace() {
        ToolAccessPolicy policy = new ToolAccessPolicy();
        policy.recordWebFetchFailure(true);
        policy.recordWebFetchFailure(true);

        ToolAccessPolicy.WebFetchDecision denied =
                policy.beforeWebFetch("https://example.com/circuit");

        assertFalse(denied.allowed());
        assertTrue(denied.hint(), denied.hint().contains("本地工作区"));
        assertFalse(denied.hint(), denied.hint().contains("WebSearch"));
    }
}
