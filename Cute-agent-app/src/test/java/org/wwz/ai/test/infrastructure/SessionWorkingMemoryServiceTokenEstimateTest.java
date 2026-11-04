package org.wwz.ai.test.infrastructure;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.memory.WorkingMemoryScopes;
import org.wwz.ai.domain.agent.memory.WorkingMemoryTurn;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;
import org.wwz.ai.infrastructure.cute.service.impl.SessionWorkingMemoryServiceImpl;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryMessageDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryTurnDao;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SessionWorkingMemoryServiceTokenEstimateTest {

    @Test
    public void tokenEstimateUsesProjectableSourceMessagesWithoutHydratingRowsAgain() {
        IWorkingMemoryTurnDao turnDao = mock(IWorkingMemoryTurnDao.class);
        IWorkingMemoryMessageDao messageDao = mock(IWorkingMemoryMessageDao.class);
        when(turnDao.selectByRequestId("req-1")).thenReturn(null);
        when(turnDao.selectMaxTurnSeq("session-1", WorkingMemoryScopes.MAIN)).thenReturn(null);
        when(turnDao.insertTurn(any(WorkingMemoryTurn.class))).thenAnswer(invocation -> {
            WorkingMemoryTurn turn = invocation.getArgument(0);
            turn.setId(11L);
            return 1;
        });
        when(messageDao.batchInsertMessages(anyList())).thenReturn(1);

        List<Message> messages = new ArrayList<>();
        Message user = Message.userMessage("hello", null);
        Message assistant = Message.builder()
                .role(RoleType.ASSISTANT)
                .content("thinking")
                .reasoningContent("reasoning")
                .toolCalls(List.of(ToolCall.builder()
                        .id("call-1")
                        .type("function")
                        .function(ToolCall.Function.builder()
                                .name("lookup")
                                .arguments("{\"q\":\"x\"}")
                                .build())
                        .build()))
                .build();
        Message tool = Message.toolMessage("result", "call-1", null);
        messages.add(user);
        messages.add(assistant);
        messages.add(tool);
        messages.add(null);
        messages.add(Message.builder().content("ignored without role").build());

        SessionWorkingMemoryServiceImpl service =
                new SessionWorkingMemoryServiceImpl(turnDao, messageDao);
        Assert.assertTrue(service.persistTurn("session-1", WorkingMemoryScopes.MAIN,
                "req-1", 7L, "react", messages));

        ArgumentCaptor<WorkingMemoryTurn> turnCaptor = ArgumentCaptor.forClass(WorkingMemoryTurn.class);
        verify(turnDao).insertTurn(turnCaptor.capture());
        Assert.assertEquals(Integer.valueOf(3), turnCaptor.getValue().getMessageCount());
        Assert.assertEquals(Integer.valueOf(new TokenCounter().estimateMessages(List.of(user, assistant, tool))),
                turnCaptor.getValue().getTokenEstimate());
    }
}
