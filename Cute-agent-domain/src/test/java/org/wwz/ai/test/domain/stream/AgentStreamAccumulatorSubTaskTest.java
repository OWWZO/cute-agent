package org.wwz.ai.test.domain.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.ArrayList;
import java.util.List;

/**
 * 子任务追加必须对空列表、非列表尾项和不可变列表都安全。
 */
public class AgentStreamAccumulatorSubTaskTest {

    @Test
    public void appendSubTaskCreatesContainerWhenTasksEmpty() {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator();
        accumulator.setResultMapTask(new ArrayList<>());
        ((List<Object>) accumulator.getResultMapTask()).clear();

        accumulator.setResultMapSubTask("sub-1");

        List<Object> tasks = accumulator.getResultMapTask();
        Assert.assertEquals(1, tasks.size());
        Assert.assertEquals(List.of("sub-1"), tasks.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void appendSubTaskReplacesNonListTail() {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator();
        accumulator.setResultMapTask(new ArrayList<>());
        List<Object> tasks = accumulator.getResultMapTask();
        tasks.set(0, "not-a-list");

        accumulator.setResultMapSubTask("sub-1");

        Assert.assertEquals(List.of("sub-1"), tasks.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void appendSubTaskCopiesImmutableTail() {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator();
        accumulator.setResultMapTask(new ArrayList<>());
        List<Object> tasks = accumulator.getResultMapTask();
        tasks.set(0, List.of("existing"));

        accumulator.setResultMapSubTask("sub-2");

        Assert.assertEquals(List.of("existing", "sub-2"), tasks.get(0));
    }
}
