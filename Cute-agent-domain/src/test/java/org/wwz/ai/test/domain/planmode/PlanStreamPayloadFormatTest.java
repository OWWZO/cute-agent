package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.stream.PlanStreamPayload;

import java.util.List;

/**
 * 计划步骤拆分回归：阶段组不得吞掉步骤里的冒号/换行，整串必须从「执行顺序」开始。
 */
public class PlanStreamPayloadFormatTest {

    @Test
    public void splitsOnFirstColonOnly() {
        PlanStreamPayload formatted = PlanStreamPayload.formatSteps(plan(
                "执行顺序1. 阶段：步骤A：补充说明"));

        Assert.assertEquals(List.of("阶段"), formatted.getStages());
        Assert.assertEquals(List.of("步骤A：补充说明"), formatted.getSteps());
    }

    @Test
    public void keepsMultilineStepContent() {
        PlanStreamPayload formatted = PlanStreamPayload.formatSteps(plan(
                "执行顺序2. 实施：第一行\n第二行"));

        Assert.assertEquals(List.of("实施"), formatted.getStages());
        Assert.assertEquals(List.of("第一行\n第二行"), formatted.getSteps());
    }

    @Test
    public void textWithPrefixIsNotSilentlyPartiallyMatched() {
        String raw = "补充 执行顺序1. 阶段：步骤A";
        PlanStreamPayload formatted = PlanStreamPayload.formatSteps(plan(raw));

        Assert.assertEquals(List.of(""), formatted.getStages());
        Assert.assertEquals(List.of(raw), formatted.getSteps());
    }

    private static PlanStreamPayload plan(String step) {
        return PlanStreamPayload.builder()
                .title("计划")
                .steps(List.of(step))
                .stepStatus(List.of("pending"))
                .notes(List.of("note"))
                .build();
    }
}
