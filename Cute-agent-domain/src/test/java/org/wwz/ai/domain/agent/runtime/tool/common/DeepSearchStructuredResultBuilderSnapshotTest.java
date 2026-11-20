package org.wwz.ai.domain.agent.runtime.tool.common;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;

/**
 * 运行中快照不能把流式报告提前标成终态，否则刷新后会出现“已完成”与
 * 后续实时帧互相覆盖的视觉回退。
 */
public class DeepSearchStructuredResultBuilderSnapshotTest {

    @Test
    public void streamingSnapshotKeepsReportNonFinal() {
        DeepSearchStructuredResultBuilder builder =
                new DeepSearchStructuredResultBuilder("总问题");
        builder.recordFinalAnswer("总问题", "第一段报告");

        DeepSearchToolOutput snapshot = builder.buildSnapshot("", false);
        DeepSearchStage report = lastReport(snapshot);

        Assert.assertFalse(report.getIsFinal());
        Assert.assertEquals("第一段报告", report.getAnswer());
    }

    @Test
    public void terminalSnapshotMarksReportFinal() {
        DeepSearchStructuredResultBuilder builder =
                new DeepSearchStructuredResultBuilder("总问题");
        builder.recordFinalAnswer("总问题", "完整报告");

        DeepSearchToolOutput snapshot = builder.buildSnapshot("", true);

        Assert.assertTrue(lastReport(snapshot).getIsFinal());
    }

    private static DeepSearchStage lastReport(DeepSearchToolOutput output) {
        return output.getStages().stream()
                .filter(stage -> DeepSearchStage.STAGE_REPORT.equals(stage.getStage()))
                .reduce((left, right) -> right)
                .orElseThrow(() -> new AssertionError("report stage missing"));
    }
}
