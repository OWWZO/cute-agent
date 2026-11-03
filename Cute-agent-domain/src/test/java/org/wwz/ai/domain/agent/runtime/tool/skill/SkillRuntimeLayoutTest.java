package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Test;

public class SkillRuntimeLayoutTest {

    @Test
    public void shouldRenderPlaceholders() {
        SkillRuntimeLayout layout = new SkillRuntimeLayout(
                SkillRuntimeOptions.builder().runtimePython("python3").build());

        String out = layout.render("generate-ppt", "run ${PYTHON} ${SKILL_DIR}/scripts/split.py");

        Assert.assertEquals("run python3 skills/generate-ppt/scripts/split.py", out);
        Assert.assertEquals("skills/generate-ppt", layout.dirOf("generate-ppt"));
    }

    @Test
    public void shouldSanitizeSkillNameSegment() {
        SkillRuntimeLayout layout = new SkillRuntimeLayout(SkillRuntimeOptions.builder().build());
        String spaced = layout.segmentOf("my skill");
        Assert.assertTrue(spaced, spaced.startsWith("my-skill-"));
        Assert.assertTrue(SkillRuntimeLayout.isSafeSegment(spaced));

        String dots = layout.segmentOf("..");
        Assert.assertTrue(dots, dots.startsWith("skill-"));
        Assert.assertTrue(SkillRuntimeLayout.isSafeSegment(dots));
    }

    @Test
    public void lossySanitizationMustNotCollideForDistinctNames() {
        SkillRuntimeLayout layout = new SkillRuntimeLayout(SkillRuntimeOptions.builder().build());

        // 纯非 ASCII 名原先全部回退到 "skill"
        Assert.assertNotEquals(layout.segmentOf("中文技能"), layout.segmentOf("另一个技能"));
        // 超长名原先截断到相同 80 字符前缀
        String prefix = "a".repeat(100);
        Assert.assertNotEquals(layout.segmentOf(prefix + "one"), layout.segmentOf(prefix + "two"));
        // 大小写变体在大小写不敏感文件系统上会落到同一目录
        Assert.assertNotEquals(layout.segmentOf("Login"), layout.segmentOf("login"));
    }

    @Test
    public void hashedSegmentsStayWithinLengthLimitAndSafeCharset() {
        SkillRuntimeLayout layout = new SkillRuntimeLayout(SkillRuntimeOptions.builder().build());

        String segment = layout.segmentOf("技能".repeat(80));

        Assert.assertTrue(segment.length() <= 80);
        Assert.assertTrue(SkillRuntimeLayout.isSafeSegment(segment));
    }
}
