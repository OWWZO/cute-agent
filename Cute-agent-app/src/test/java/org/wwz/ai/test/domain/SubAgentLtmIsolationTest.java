package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.wwz.ai.domain.agent.memory.CompactionBudget;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.memory.ltm.LtmManager;
import org.wwz.ai.domain.agent.memory.ltm.LtmServices;
import org.wwz.ai.domain.agent.memory.ltm.MemoryFlushService;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryCompactionDao;
import org.wwz.ai.infrastructure.cute.service.impl.SessionContextCompactionServiceImpl;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SubAgentLtmIsolationTest {

    @Test
    public void subAgentCompactionSkipsLongTermMemoryHooks() {
        MemoryFlushService flushService = mock(MemoryFlushService.class);
        LtmManager ltmManager = mock(LtmManager.class);
        CuteRuntimeDependencies dependencies = mock(CuteRuntimeDependencies.class);
        ObjectProvider<CuteRuntimeDependencies> dependenciesProvider = mock(ObjectProvider.class);
        when(dependenciesProvider.getObject()).thenReturn(dependencies);
        when(dependencies.requireCuteConfig()).thenReturn(mock(CuteConfig.class));
        when(dependencies.resolveLlmSettings(anyString())).thenReturn(
                LLMSettings.builder().maxInputTokens(20).maxTokens(1).build());
        when(dependencies.getOptionalMemoryFlushService()).thenReturn(flushService);
        when(dependencies.getOptionalLtmManager()).thenReturn(ltmManager);

        // 子会话判据已从 memory_scope 换成 session_kind，这里给账本喂一条 SUBAGENT 会话行。
        IExecutionLedgerReadRepository ledger = mock(IExecutionLedgerReadRepository.class);
        when(ledger.querySessionEntity("sub_agent-1")).thenReturn(
                DialogueSession.builder()
                        .sessionId("sub_agent-1")
                        .sessionKind(SessionKind.SUBAGENT.code())
                        .build());

        SessionContextCompactionServiceImpl compaction = new SessionContextCompactionServiceImpl(
                dependenciesProvider,
                mock(SessionWorkingMemoryService.class),
                mock(IWorkingMemoryCompactionDao.class),
                CompactionBudget.defaults().toBuilder()
                        .llmEnabled(false)
                        .microEnabled(false)
                        .keepMinTokens(1)
                        .keepMinTextMessages(1)
                        .keepMaxTokens(10)
                        .maxConsecutiveFailures(1)
                        .microKeepRecentToolResults(1)
                        .microToolResultMaxChars(100)
                        .persistProjection(false)
                        .auditEnabled(false)
                        .auditStoreMessages(false)
                        .build(),
                ledger);

        LtmServices.bind(null, flushService);
        try {
            // scope 已统一为 main；子会话身份由 session_kind 判定
            List<Message> compacted = compaction.applyIfNeeded(
                    "sub_agent-1",
                    "main",
                    "request-1",
                    List.of(Message.userMessage("x".repeat(200), null)));

            Assert.assertNotNull(compacted);
        } finally {
            LtmServices.bind(null, null);
        }

        verifyNoInteractions(flushService, ltmManager);
    }
}
