package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillFileNotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * available_files 提示必须保留描述符顺序，并与调用方的可变集合解耦。
 */
public class SkillFileNotFoundExceptionTest {

    @Test
    public void preservesInsertionOrderAndDetachesMutableInput() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        List<String> scripts = new ArrayList<>(List.of("deploy.sh", "build.sh"));
        source.put("scripts", scripts);
        source.put("references", new ArrayList<>(List.of("guide.md")));

        SkillFileNotFoundException exception = new SkillFileNotFoundException("missing.md", source);
        scripts.add("late.sh");

        Assert.assertEquals(List.of("scripts", "references"), new ArrayList<>(exception.getAvailableFiles().keySet()));
        Assert.assertEquals(List.of("deploy.sh", "build.sh"), exception.getAvailableFiles().get("scripts"));
    }

    @Test
    public void exposesImmutableCollections() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put("scripts", new ArrayList<>(List.of("deploy.sh")));
        SkillFileNotFoundException exception = new SkillFileNotFoundException("missing.md", source);

        try {
            exception.getAvailableFiles().put("assets", List.of("logo.png"));
            Assert.fail("expected immutable map");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            exception.getAvailableFiles().get("scripts").add("late.sh");
            Assert.fail("expected immutable list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void nullMapFallsBackToEmpty() {
        Assert.assertTrue(new SkillFileNotFoundException("missing.md", null).getAvailableFiles().isEmpty());
    }
}
