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
        Assert.assertEquals("etc-passwd", layout.segmentOf("../../etc/passwd"));
        Assert.assertEquals("skills/etc-passwd", layout.relativeDirOf("../../etc/passwd"));
        Assert.assertEquals("a-b", layout.segmentOf("a/b"));
        Assert.assertEquals("a-b", layout.segmentOf("a\\b"));
    }

    @Test
    public void pureDotsFallBackToConstant() {
        Assert.assertEquals("skill", layout.segmentOf(".."));
        Assert.assertEquals("skill", layout.segmentOf("."));
        Assert.assertEquals("skill", layout.segmentOf("..."));
        Assert.assertEquals("skills/skill", layout.relativeDirOf(".."));
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
