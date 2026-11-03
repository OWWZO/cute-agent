package org.wwz.ai.test.domain.memory;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryScope;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryStore;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryWriteResult;
import org.wwz.ai.domain.agent.memory.ltm.LtmAgentForkSupport;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkParity;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkRunResult;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.List;

/**
 * fork 快照回归：before 快照失败必须整体失败，不能拿部分快照去算 added/removed。
 */
public class LtmForkSnapshotFailureTest {

    @Test
    public void snapshotFailureFailsForkInsteadOfReportingCorruptDiff() {
        CuratedMemoryStore failingStore = new CuratedMemoryStore() {
            @Override
            public CuratedMemoryWriteResult add(LtmOwner owner, CuratedMemoryScope scope, String content,
                                                String sourceSessionId, String sourceRequestId,
                                                String writeOrigin) {
                return null;
            }

            @Override
            public CuratedMemoryWriteResult replace(LtmOwner owner, CuratedMemoryScope scope, String oldText,
                                                    String content, String sourceSessionId,
                                                    String sourceRequestId, String writeOrigin) {
                return null;
            }

            @Override
            public CuratedMemoryWriteResult remove(LtmOwner owner, CuratedMemoryScope scope, String oldText,
                                                   String sourceSessionId, String sourceRequestId,
                                                   String writeOrigin) {
                return null;
            }

            @Override
            public List<org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryEntry> listActive(
                    LtmOwner owner, CuratedMemoryScope scope, int limit) {
                throw new IllegalStateException("memory store unavailable");
            }

            @Override
            public String formatSnapshot(LtmOwner owner) {
                return "";
            }

            @Override
            public int usedChars(LtmOwner owner, CuratedMemoryScope scope) {
                return 0;
            }

            @Override
            public int charLimit(CuratedMemoryScope scope) {
                return 0;
            }
        };

        LtmForkRunResult result = LtmAgentForkSupport.runParityFork(
                CuteRuntimeDependencies.builder().build(),
                failingStore,
                LtmOwner.user("u-1"),
                "sess-1",
                "req-1",
                LtmForkParity.forFlush("system", new ToolCollection(), List.of()),
                "flush memory",
                1,
                5,
                "flush");

        Assert.assertEquals(LtmForkRunResult.STATUS_FAILED, result.getStatus());
        Assert.assertTrue(String.valueOf(result.getErrorMessage()).contains("memory snapshot failed"));
        Assert.assertEquals(0, result.getAppliedCount());
    }
}
