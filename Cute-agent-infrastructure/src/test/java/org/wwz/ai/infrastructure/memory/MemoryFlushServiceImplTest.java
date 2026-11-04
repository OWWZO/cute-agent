package org.wwz.ai.infrastructure.memory;

import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryStore;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkExecutionEvent;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.infrastructure.dao.cute.ILtmForkExecutionDao;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MemoryFlushServiceImplTest {

    @Test
    public void requestKeyIsReleasedAfterEachFlushAttempt() {
        List<LtmForkExecutionEvent> events = new ArrayList<>();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ILtmForkExecutionDao dao = event -> {
            events.add(event);
            return 1;
        };
        beanFactory.registerSingleton("ltmForkExecutionDao", dao);

        MemoryFlushServiceImpl service = new MemoryFlushServiceImpl(
                beanFactory.getBeanProvider(CuteRuntimeDependencies.class),
                beanFactory.getBeanProvider(CuratedMemoryStore.class),
                beanFactory.getBeanProvider(IExecutionLedgerReadRepository.class),
                beanFactory.getBeanProvider(ILtmForkExecutionDao.class));
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            messages.add(Message.userMessage("turn-" + i, null));
        }

        service.flushBeforeCompact(
                "session-1", "request-1", LtmOwner.user("user-1"), messages, null, null);
        service.flushBeforeCompact(
                "session-1", "request-1", LtmOwner.user("user-1"), messages, null, null);

        assertEquals(2, events.size());
        assertEquals("store-null", events.get(0).getSkipReason());
        assertEquals("store-null", events.get(1).getSkipReason());
    }
}
