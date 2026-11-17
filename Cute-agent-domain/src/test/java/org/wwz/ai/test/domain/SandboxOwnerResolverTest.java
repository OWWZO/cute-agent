package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionIdentity;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.common.SandboxOwnerResolver;

/**
 * 沙箱归属回归：主 Agent 与独立子 Agent 必须解析到同一个稳定用户 owner，不能退化成会话 owner。
 */
public class SandboxOwnerResolverTest {

    @Test
    public void parentAndChildShouldResolveSameUserSandboxOwner() {
        SubAgentSessionPort port = new FixedSessionUserPort("sub_agent-1", "user-1");
        AgentContext parent = AgentContext.builder()
                .requestId("req-parent")
                .sessionId("parent-session")
                .userId("user-1")
                .query("q")
                .toolCollection(new ToolCollection())
                .runtimeDependencies(newRuntimeDependencies(port))
                .build();

        AgentContext child = SubAgentContextFactory.create(
                parent, "prompt", "desc", new ToolCollection(), "agent-1", "general-purpose");

        Assert.assertEquals("user-1", child.getUserId());
        Assert.assertNull("子 Agent 长期记忆仍必须隔离", child.getLtmOwner());
        Assert.assertEquals("user:user-1", SandboxOwnerResolver.resolve(parent));
        Assert.assertEquals("user:user-1", SandboxOwnerResolver.resolve(child));
    }

    @Test
    public void independentChildShouldRecoverUserFromPersistedSession() {
        AgentContext resumedChild = AgentContext.builder()
                .requestId("req-child")
                .sessionId("sub_agent-2")
                .runtimeDependencies(newRuntimeDependencies(
                        new FixedSessionUserPort("sub_agent-2", "user-1")))
                .build();

        Assert.assertEquals("user:user-1", SandboxOwnerResolver.resolve(resumedChild));
    }

    @Test
    public void unresolvedUserShouldFailClosedInsteadOfUsingSessionOwner() {
        AgentContext context = AgentContext.builder()
                .requestId("req-child")
                .sessionId("sub_agent-missing")
                .runtimeDependencies(newRuntimeDependencies(
                        new FixedSessionUserPort("another-session", null)))
                .build();

        Assert.assertNull(SandboxOwnerResolver.resolve(context));
    }

    private static CuteRuntimeDependencies newRuntimeDependencies(SubAgentSessionPort port) {
        return CuteRuntimeDependencies.builder().subAgentSessionPort(port).build();
    }

    private static final class FixedSessionUserPort implements SubAgentSessionPort {
        private final String sessionId;
        private final String userId;

        private FixedSessionUserPort(String sessionId, String userId) {
            this.sessionId = sessionId;
            this.userId = userId;
        }

        @Override
        public void ensureChildSession(SubAgentSessionIdentity identity) {
        }

        @Override
        public String findSessionUserId(String requestedSessionId) {
            return sessionId.equals(requestedSessionId) ? userId : null;
        }
    }
}
