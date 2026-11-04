package org.wwz.ai.infrastructure.cute.service.impl;

import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SessionContextCompactionSnapshotTest {

    @Test
    public void snapshotCopyAcceptsNullMessagesAndIsDefensive() {
        List<Message> messages = new ArrayList<>(Arrays.asList(
                Message.userMessage("hello", null),
                null));

        List<Message> snapshot = SessionContextCompactionServiceImpl.copyMessagesAllowingNulls(messages);

        assertEquals(2, snapshot.size());
        assertNull(snapshot.get(1));
        messages.clear();
        assertEquals(2, snapshot.size());
    }
}
