package org.wwz.ai.test.domain.memory;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.WorkingMemoryMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemoryProjector;
import org.wwz.ai.domain.agent.runtime.dto.Message;

import java.util.List;

/**
 * hydrate 必须保留 originMessageKey，否则重新投影会生成不同的跨投影身份。
 */
public class WorkingMemoryProjectorOriginKeyTest {

    @Test
    public void hydrateKeepsOriginMessageKey() {
        WorkingMemoryProjector projector = new WorkingMemoryProjector();
        Message original = Message.userMessage("q", null, "origin-1");

        List<WorkingMemoryMessage> rows = projector.project(List.of(original), "s1", "r1", 1L);
        Assert.assertEquals("origin-1", rows.get(0).getOriginMessageKey());

        List<Message> hydrated = projector.hydrate(rows);
        Assert.assertEquals("origin-1", hydrated.get(0).getOriginMessageKey());
    }
}
