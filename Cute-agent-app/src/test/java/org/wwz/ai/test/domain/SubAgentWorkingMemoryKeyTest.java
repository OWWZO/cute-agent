package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.memory.WorkingMemoryScopes;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子会话记忆键统一：{@code (childSessionId, 'main')}，与主会话同构。
 *
 * <p>父派发子 Agent 与用户从会话树点进子会话续聊，读写的是同一份记忆。</p>
 */
public class SubAgentWorkingMemoryKeyTest {

    @Test
    public void hasPersistedMemoryMustQueryChildSessionIdWithMainScope() {
        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        when(workingMemory.loadReadyMessages(anyString(), anyString(), any()))
                .thenReturn(List.of(Message.userMessage("prior", null)));
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());
        runner.setSessionWorkingMemoryService(workingMemory);

        Assert.assertTrue(runner.hasPersistedMemory("sub_agent-1"));

        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        verify(workingMemory).loadReadyMessages(sessionId.capture(), scope.capture(), any());
        Assert.assertEquals("必须用子会话自己的 sessionId 查，不能挂在父会话名下",
                "sub_agent-1", sessionId.getValue());
        Assert.assertEquals("子会话记忆与主会话同构，scope 统一为 main",
                WorkingMemoryScopes.MAIN, scope.getValue());
    }

    @Test
    public void hasPersistedMemoryReturnsFalseWhenNoRows() {
        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        when(workingMemory.loadReadyMessages(anyString(), anyString(), any())).thenReturn(List.of());
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());
        runner.setSessionWorkingMemoryService(workingMemory);

        Assert.assertFalse(runner.hasPersistedMemory("sub_agent-2"));
    }

    @Test
    public void hasPersistedMemoryToleratesBlankAndMissingService() {
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());
        runner.setSessionWorkingMemoryService(null);
        Assert.assertFalse("未注入服务时不得抛异常", runner.hasPersistedMemory("sub_agent-3"));

        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        runner.setSessionWorkingMemoryService(workingMemory);
        Assert.assertFalse(runner.hasPersistedMemory(""));
        Assert.assertFalse(runner.hasPersistedMemory(null));
    }
}
