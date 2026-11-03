package org.wwz.ai.test.domain.tool.mcp;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.mcp.model.McpToolNamePolicy;

/**
 * 片段内不能残留 "__"，否则 (server=a__b, tool=c) 与 (server=a, tool=b__c) 会解析成同一个 FQ 名。
 */
public class McpToolNamePolicySegmentTest {

    @Test
    public void underscoreRunsAreCollapsed() {
        Assert.assertEquals("a_b", McpToolNamePolicy.normalizeSegment("a__b"));
        Assert.assertEquals("a_b_c", McpToolNamePolicy.normalizeSegment("a___b__c"));
    }

    @Test
    public void ambiguousPairsProduceDistinctQualifiedNames() {
        String left = McpToolNamePolicy.buildQualifiedName("a__b", "c");
        String right = McpToolNamePolicy.buildQualifiedName("a", "b__c");

        Assert.assertNotEquals(left, right);
        Assert.assertEquals("a_b", McpToolNamePolicy.parseServerKey(left));
        Assert.assertEquals("a", McpToolNamePolicy.parseServerKey(right));
    }

    @Test
    public void safeNamesAreUnchanged() {
        Assert.assertEquals("server-1", McpToolNamePolicy.normalizeSegment("server-1"));
        Assert.assertEquals("mcp__server-1__read_file",
                McpToolNamePolicy.buildQualifiedName("server-1", "read_file"));
    }
}
