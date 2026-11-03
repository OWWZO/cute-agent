package org.wwz.ai.domain.agent.runtime.capability;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.repository.ISessionCapabilityRepository;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * 仓储实现违约返回 null（或 kind 对应 null）时，读取路径必须按空差集降级而不是 NPE。
 */
public class SessionCapabilityServiceNullOverrideTest {

    @Test
    public void capabilitiesShouldTreatNullOverridesAsEmpty() {
        SessionCapabilityService service = new SessionCapabilityService(
                new NullOverrideRepository(null), null, mock(McpToolExecutor.class));

        SessionCapabilityService.SessionCapabilitiesView view = service.capabilities("session-1");

        Assert.assertTrue(view.skills().isEmpty());
        Assert.assertTrue(view.mcpServers().isEmpty());
    }

    @Test
    public void capabilitiesShouldTreatNullKindMapAsEmpty() {
        Map<String, Map<String, Boolean>> overrides = new HashMap<>();
        overrides.put(SessionCapabilityService.KIND_SKILL, null);
        overrides.put(SessionCapabilityService.KIND_MCP, null);
        SessionCapabilityService service = new SessionCapabilityService(
                new NullOverrideRepository(overrides), null, mock(McpToolExecutor.class));

        SessionCapabilityService.SessionCapabilitiesView view = service.capabilities("session-1");

        Assert.assertTrue(view.skills().isEmpty());
        Assert.assertTrue(view.mcpServers().isEmpty());
    }

    @Test
    public void loadDisabledShouldTreatNullOverridesAsEmpty() {
        SessionCapabilityService service = new SessionCapabilityService(
                new NullOverrideRepository(null), null, mock(McpToolExecutor.class));

        SessionCapabilityService.DisabledSets disabled = service.loadDisabled("session-1");

        Assert.assertTrue(disabled.skills().isEmpty());
        Assert.assertTrue(disabled.mcps().isEmpty());
    }

    private static final class NullOverrideRepository implements ISessionCapabilityRepository {

        private final Map<String, Map<String, Boolean>> overrides;

        private NullOverrideRepository(Map<String, Map<String, Boolean>> overrides) {
            this.overrides = overrides;
        }

        @Override
        public Map<String, Map<String, Boolean>> findOverrides(String sessionId) {
            return overrides;
        }

        @Override
        public void upsert(String sessionId, String kind, String refId, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionCapabilityRow> listBySession(String sessionId) {
            return List.of();
        }
    }
}
