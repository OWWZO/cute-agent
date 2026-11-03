package org.wwz.ai.test.domain.capability;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.repository.ISessionCapabilityRepository;
import org.wwz.ai.domain.agent.runtime.capability.SessionCapabilityService;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillDefinition;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRegistry;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 读写两侧必须使用同一套 trim 规则，否则带空白的请求参数会让开关静默失效。
 */
public class SessionCapabilityServiceNormalizationTest {

    @Test
    public void paddedSessionIdStillReadsTrimmedOverride() {
        ISessionCapabilityRepository repository = mock(ISessionCapabilityRepository.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        McpToolExecutor mcp = mock(McpToolExecutor.class);
        when(skills.isEnabled()).thenReturn(true);
        when(skills.listSkills()).thenReturn(List.of(SkillDefinition.builder().name("demo").build()));
        when(mcp.discoverConfiguredTools()).thenReturn(List.of());
        when(repository.findOverrides("s1")).thenReturn(Map.of("skill", Map.of("demo", false)));

        SessionCapabilityService service = new SessionCapabilityService(repository, skills, mcp);

        service.setEnabled(" s1 ", "skill", " demo ", false);
        verify(repository).upsert("s1", "skill", "demo", false);

        SessionCapabilityService.SessionCapabilitiesView view = service.capabilities(" s1 ");
        Assert.assertEquals(1, view.skills().size());
        Assert.assertFalse(view.skills().get(0).enabled());

        Assert.assertTrue(service.loadDisabled(" s1 ").isSkillDisabled("demo"));
    }
}
