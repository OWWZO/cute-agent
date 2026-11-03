package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * disabled / tags 过滤必须与 category / source 一样大小写不敏感。
 */
public class SkillCatalogSearchCaseTest {

    @Test
    public void disabledNamesAreMatchedCaseInsensitively() {
        SkillDescriptor descriptor = SkillDescriptor.builder()
                .name("MySkill")
                .description("demo")
                .build();
        SkillQuery query = SkillQuery.builder()
                .disabledNames(new LinkedHashSet<>(Set.of("mySKILL")))
                .build();

        List<SkillSearchHit> hits = SkillCatalogSearch.search(List.of(descriptor), query);

        Assert.assertTrue("大小写不同的禁用名也必须过滤", hits.isEmpty());
    }

    @Test
    public void tagFilterIsMatchedCaseInsensitively() {
        SkillDescriptor descriptor = SkillDescriptor.builder()
                .name("python-helper")
                .description("demo")
                .tags(Set.of("python"))
                .build();
        SkillQuery query = SkillQuery.builder()
                .tags(new LinkedHashSet<>(Set.of("Python")))
                .build();

        List<SkillSearchHit> hits = SkillCatalogSearch.search(List.of(descriptor), query);

        Assert.assertEquals(1, hits.size());
        Assert.assertEquals("python-helper", hits.get(0).descriptor().getName());
    }
}
