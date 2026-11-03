package org.wwz.ai.test.domain.tool;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.skill.SkillViewTool;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillCatalog;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillDescriptor;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillQuery;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRef;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillSearchHit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * skill_view 的禁用判定必须使用注册表规范名。
 * <p>回归攻击路径：禁用集合按规范名存放，但 resolve 支持 source:name 等别名；
 * 旧实现拿原始输入做比对，别名可绕过会话级禁用。</p>
 */
public class SkillViewToolDisabledSkillTest {

    private static final String CANONICAL = "sql-analysis";

    @Test
    public void aliasCannotBypassDisabledSkill() {
        SkillViewTool tool = new SkillViewTool(aliasCatalog(), null, null, null, null);
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .disabledSkillNames(Set.of(CANONICAL))
                .build();

        Object result = tool.execute(Map.of("name", "builtin:" + CANONICAL), context);

        Assert.assertTrue(result instanceof ToolResultPayload);
        ToolResultPayload payload = (ToolResultPayload) result;
        Assert.assertEquals(Boolean.TRUE, payload.getFailed());
        Assert.assertTrue("应提示 skill 已被关闭，实际: " + payload.getErrorMsg(),
                payload.getErrorMsg().contains("已在本会话关闭"));
        Assert.assertTrue("提示应使用规范名，实际: " + payload.getErrorMsg(),
                payload.getErrorMsg().contains(CANONICAL));
    }

    private static SkillCatalog aliasCatalog() {
        SkillRef ref = SkillRef.of(SkillRef.SOURCE_BUILTIN, CANONICAL, CANONICAL);
        SkillDescriptor descriptor = SkillDescriptor.builder()
                .ref(ref)
                .name(CANONICAL)
                .description("test")
                .build();
        return new SkillCatalog() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public List<SkillDescriptor> list() {
                return List.of(descriptor);
            }

            @Override
            public List<SkillSearchHit> search(SkillQuery query) {
                return List.of();
            }

            @Override
            public SkillRef resolve(String identifier) {
                return ref;
            }

            @Override
            public Optional<SkillDescriptor> find(String identifier) {
                return Optional.of(descriptor);
            }

            @Override
            public SkillDescriptor getRequired(String identifier) {
                return descriptor;
            }
        };
    }
}
