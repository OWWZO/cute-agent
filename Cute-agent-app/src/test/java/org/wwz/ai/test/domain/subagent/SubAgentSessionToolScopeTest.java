package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentSessionToolScope;

/**
 * 子会话工具池 fail-closed 回归：类型已记录但定义下架时，不得回退全量工具池。
 */
public class SubAgentSessionToolScopeTest {

    private final IExecutionLedgerReadRepository repository = Mockito.mock(IExecutionLedgerReadRepository.class);
    private final SubAgentRegistry registry = new SubAgentRegistry();
    private final SubAgentSessionToolScope scope = new SubAgentSessionToolScope(registry, repository);

    @Test
    public void recordedButUnresolvableTypeIsFailClosed() {
        Mockito.when(repository.querySessionEntity("sub-1")).thenReturn(session("sub-1", "removed-agent"));

        SubAgentSessionToolScope.Resolution resolution = scope.resolve("sub-1");

        Assert.assertTrue("已记录类型但注册表缺失时必须 fail-closed", resolution.isUnresolvable());
        Assert.assertEquals(SubAgentSessionToolScope.Status.UNRESOLVABLE, resolution.status());
    }

    @Test
    public void missingLegacyTypeKeepsFullPool() {
        Mockito.when(repository.querySessionEntity("sub-2")).thenReturn(session("sub-2", null));

        Assert.assertEquals(SubAgentSessionToolScope.Status.NOT_APPLICABLE,
                scope.resolve("sub-2").status());
    }

    @Test
    public void resolvableTypeReturnsDefinition() {
        Mockito.when(repository.querySessionEntity("sub-3")).thenReturn(session("sub-3", "general-purpose"));

        SubAgentSessionToolScope.Resolution resolution = scope.resolve("sub-3");

        Assert.assertTrue(resolution.isResolved());
        Assert.assertEquals("general-purpose", resolution.definition().getAgentType());
    }

    private DialogueSession session(String sessionId, String subAgentType) {
        return DialogueSession.builder()
                .sessionId(sessionId)
                .sessionKind(SessionKind.SUBAGENT.code())
                .subAgentType(subAgentType)
                .build();
    }
}
