package org.wwz.ai.test.domain.security;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeLayout;

/**
 * Skill 目录段安全回归：清洗结果必须始终是单段、非 . / ..、不含分隔符。
 */
public class SkillRuntimeLayoutSecurityTest {

    private final SkillRuntimeLayout layout = new SkillRuntimeLayout(null);

    @Test
    public void traversalNamesCollapseIntoSingleSafeSegment() {
        String traversal = layout.segmentOf("../../etc/passwd");
        Assert.assertTrue(traversal, traversal.startsWith("etc-passwd-"));
        Assert.assertFalse(traversal.contains("/"));
        Assert.assertFalse(traversal.contains("\\"));
        Assert.assertEquals("skills/" + traversal, layout.relativeDirOf("../../etc/passwd"));

        // 不同原始名不再因为清洗成同一个 "a-b" 而共享目录
        String slash = layout.segmentOf("a/b");
        String backslash = layout.segmentOf("a\\b");
        Assert.assertTrue(slash, slash.startsWith("a-b-"));
        Assert.assertTrue(backslash, backslash.startsWith("a-b-"));
        Assert.assertNotEquals(slash, backslash);
    }

    @Test
    public void pureDotsFallBackToHashedConstant() {
        String dotDot = layout.segmentOf("..");
        Assert.assertTrue(dotDot, dotDot.startsWith("skill-"));
        Assert.assertTrue(dotDot, dotDot.matches("[A-Za-z0-9._-]+"));
        Assert.assertNotEquals(dotDot, layout.segmentOf("."));
        Assert.assertNotEquals(dotDot, layout.segmentOf("..."));
        Assert.assertEquals("skills/" + dotDot, layout.relativeDirOf(".."));
    }

    @Test
    public void emptyNameFallsBackToConstant() {
        Assert.assertEquals("skill", layout.segmentOf(null));
        Assert.assertEquals("skill", layout.segmentOf("   "));
    }

    @Test
    public void normalNamesArePreserved() {
        Assert.assertEquals("my-skill_1.0", layout.segmentOf("my-skill_1.0"));
    }
}
