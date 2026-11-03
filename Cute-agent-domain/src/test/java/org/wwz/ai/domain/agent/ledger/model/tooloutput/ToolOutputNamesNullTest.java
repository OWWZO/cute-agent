package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import org.junit.Assert;
import org.junit.Test;

/**
 * 账本 tool_name 可能为空；判定入口必须返回 false，而不是让 Set.of(...).contains(null) 抛 NPE。
 */
public class ToolOutputNamesNullTest {

    @Test
    public void nullToolNameIsNotPersisted() {
        Assert.assertFalse(ToolOutputNames.isPersistedTool(null));
        Assert.assertFalse(ToolOutputNames.isPersistedTool(""));
        Assert.assertTrue(ToolOutputNames.isPersistedTool(ToolOutputNames.DEEP_SEARCH));
    }
}
