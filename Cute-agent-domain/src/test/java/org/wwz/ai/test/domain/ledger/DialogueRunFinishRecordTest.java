package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;

/**
 * run 结束命令的终态校验：拒绝 null / RUNNING / 未知状态，允许 WAITING_INPUT。
 */
public class DialogueRunFinishRecordTest {

    @Test
    public void nullStatusIsRejected() {
        try {
            DialogueRunFinishRecord.builder().requestId("req-1").build();
            Assert.fail("status 不能为空");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("status"));
        }
    }

    @Test
    public void runningStatusIsRejected() {
        try {
            DialogueRunFinishRecord.builder()
                    .requestId("req-1")
                    .status(ExecutionLedgerConstants.STATUS_RUNNING)
                    .build();
            Assert.fail("RUNNING 不能作为结束状态写入");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void unknownStatusIsRejected() {
        try {
            DialogueRunFinishRecord.builder().requestId("req-1").status(99).build();
            Assert.fail("未知状态不能写入");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void waitingInputIsAcceptedAsIntermediateFinish() {
        DialogueRunFinishRecord record = DialogueRunFinishRecord.builder()
                .requestId("req-1")
                .status(ExecutionLedgerConstants.STATUS_WAITING_INPUT)
                .build();
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_WAITING_INPUT),
                record.getStatus());
    }

    @Test
    public void terminalStatusesAreAccepted() {
        for (int status : new int[]{
                ExecutionLedgerConstants.STATUS_SUCCESS,
                ExecutionLedgerConstants.STATUS_FAILED,
                ExecutionLedgerConstants.STATUS_TIMEOUT,
                ExecutionLedgerConstants.STATUS_STOPPED}) {
            Assert.assertEquals(Integer.valueOf(status), DialogueRunFinishRecord.builder()
                    .requestId("req-1")
                    .status(status)
                    .build()
                    .getStatus());
        }
    }
}
