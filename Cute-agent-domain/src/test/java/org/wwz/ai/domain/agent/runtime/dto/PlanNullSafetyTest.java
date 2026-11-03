package org.wwz.ai.domain.agent.runtime.dto;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plan 会从账本反序列化；历史/残缺载荷不能让只读查询或更新路径 NPE/越界。
 */
public class PlanNullSafetyTest {

    @Test
    public void currentStepShouldTolerateNullAndShortStatusLists() {
        Assert.assertEquals("", Plan.builder().steps(null).stepStatus(null).build().getCurrentStep());
        Assert.assertEquals("", Plan.builder()
                .steps(List.of("a", "b"))
                .stepStatus(null)
                .build()
                .getCurrentStep());
        Assert.assertEquals("", Plan.builder()
                .steps(List.of("a"))
                .stepStatus(List.of("not_started", "in_progress"))
                .build()
                .getCurrentStep());
        Assert.assertEquals("a", Plan.builder()
                .steps(List.of("a"))
                .stepStatus(List.of("in_progress"))
                .build()
                .getCurrentStep());
    }

    @Test
    public void updateStepStatusShouldValidateAllThreeLists() {
        assertIllegalArgument(() -> Plan.builder()
                .steps(null)
                .stepStatus(new ArrayList<>())
                .notes(new ArrayList<>())
                .build()
                .updateStepStatus(0, "completed", ""));
        assertIllegalArgument(() -> Plan.builder()
                .steps(List.of("a", "b"))
                .stepStatus(List.of("not_started"))
                .notes(List.of(""))
                .build()
                .updateStepStatus(1, "completed", ""));
        assertIllegalArgument(() -> Plan.builder()
                .steps(List.of("a"))
                .stepStatus(List.of("not_started"))
                .notes(null)
                .build()
                .updateStepStatus(0, "completed", ""));
    }

    @Test
    public void updateShouldTolerateNullListsAndNullSteps() {
        Plan plan = Plan.builder().steps(null).stepStatus(null).notes(null).build();

        plan.update("新标题", Arrays.asList(null, "b"));

        Assert.assertEquals("新标题", plan.getTitle());
        Assert.assertEquals(Arrays.asList(null, "b"), plan.getSteps());
        Assert.assertEquals(Arrays.asList("not_started", "not_started"), plan.getStepStatus());
        Assert.assertEquals(Arrays.asList("", ""), plan.getNotes());
    }

    @Test
    public void updateShouldCopyCallerList() {
        Plan plan = Plan.create("标题", List.of("a"));
        List<String> callerSteps = new ArrayList<>(List.of("a"));

        plan.update(null, callerSteps);
        callerSteps.add("b");

        Assert.assertEquals(1, plan.getSteps().size());
        Assert.assertEquals("a", plan.getSteps().get(0));
    }

    private void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
