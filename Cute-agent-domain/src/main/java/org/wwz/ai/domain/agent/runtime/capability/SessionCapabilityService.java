package org.wwz.ai.domain.agent.runtime.capability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.adapter.repository.ISessionCapabilityRepository;
import org.wwz.ai.domain.agent.runtime.tool.mcp.model.McpToolInfo;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillDefinition;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话级 skill / MCP 能力清单与差集开关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCapabilityService {

    public static final String KIND_SKILL = "skill";
    public static final String KIND_MCP = "mcp";

    /** MCP 发现结果短缓存：capabilities 会被 UI 频繁读取，避免每次刷新都全量发现。 */
    private static final long MCP_DISCOVERY_CACHE_TTL_MS = 5_000L;

    private final ISessionCapabilityRepository repository;
    private final SkillRegistry skillRegistry;
    private final McpToolExecutor mcpToolExecutor;

    private volatile List<McpToolInfo> cachedDiscoveredTools;
    private volatile long discoveredAtMs;

    public SessionCapabilitiesView capabilities(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        Map<String, Map<String, Boolean>> overrides = normalizedSessionId == null
                ? Map.of()
                : repository.findOverrides(normalizedSessionId);
        Map<String, Boolean> skillOv = overridesFor(overrides, KIND_SKILL);
        Map<String, Boolean> mcpOv = overridesFor(overrides, KIND_MCP);

        List<CapabilityItem> skills = new ArrayList<>();
        if (skillRegistry != null && skillRegistry.isEnabled()) {
            for (SkillDefinition def : skillRegistry.listSkills()) {
                if (def == null || StringUtils.isBlank(def.getName())) {
                    continue;
                }
                boolean enabled = skillOv.getOrDefault(def.getName().trim(), true);
                skills.add(new CapabilityItem(def.getName(), def.getName(), enabled, "system"));
            }
        }

        List<CapabilityItem> mcps = new ArrayList<>();
        Set<String> seenMcp = new LinkedHashSet<>();
        try {
            List<McpToolInfo> tools = discoverToolsCached();
            for (McpToolInfo t : tools) {
                if (t == null) {
                    continue;
                }
                String mcpId = StringUtils.trimToNull(StringUtils.defaultIfBlank(t.getMcpId(), t.getServerKey()));
                if (mcpId == null || !seenMcp.add(mcpId)) {
                    continue;
                }
                String label = StringUtils.defaultIfBlank(t.getServerKey(), mcpId);
                boolean enabled = mcpOv.getOrDefault(mcpId, true);
                mcps.add(new CapabilityItem(mcpId, label, enabled, "platform"));
            }
        } catch (Exception e) {
            // 发现失败仍降级为空 MCP 列表，但保留告警，避免与「确实没配置」混淆。
            log.warn("MCP 工具发现失败，按空列表降级: sessionId={}", sessionId, e);
        }

        return new SessionCapabilitiesView(false, skills, mcps);
    }

    private List<McpToolInfo> discoverToolsCached() {
        long now = System.currentTimeMillis();
        List<McpToolInfo> cached = cachedDiscoveredTools;
        if (cached != null && now - discoveredAtMs < MCP_DISCOVERY_CACHE_TTL_MS) {
            return cached;
        }
        List<McpToolInfo> discovered = mcpToolExecutor.discoverConfiguredTools();
        cachedDiscoveredTools = discovered == null ? List.of() : List.copyOf(discovered);
        discoveredAtMs = now;
        return cachedDiscoveredTools;
    }

    public void setEnabled(String sessionId, String kind, String refId, boolean enabled) {
        if (StringUtils.isAnyBlank(sessionId, kind, refId)) {
            throw new IllegalArgumentException("sessionId/kind/refId 不能为空");
        }
        String k = kind.trim().toLowerCase();
        if (!KIND_SKILL.equals(k) && !KIND_MCP.equals(k)) {
            throw new IllegalArgumentException("kind 仅支持 skill|mcp");
        }
        repository.upsert(sessionId.trim(), k, refId.trim(), enabled);
    }

    /** 返回本会话禁用的 skill 名 / mcpId（未配置 overrides 则空集 = 全开）。 */
    public DisabledSets loadDisabled(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return DisabledSets.empty();
        }
        Map<String, Map<String, Boolean>> overrides = repository.findOverrides(normalizedSessionId);
        Set<String> disabledSkills = new HashSet<>();
        Set<String> disabledMcps = new HashSet<>();
        overridesFor(overrides, KIND_SKILL).forEach((id, en) -> {
            if (Boolean.FALSE.equals(en)) {
                disabledSkills.add(id);
            }
        });
        overridesFor(overrides, KIND_MCP).forEach((id, en) -> {
            if (Boolean.FALSE.equals(en)) {
                disabledMcps.add(id);
            }
        });
        return new DisabledSets(disabledSkills, disabledMcps);
    }

    /** 仓储契约要求返回非 null；这里仍兜底，避免实现方违约把读取路径打成 NPE。 */
    private static Map<String, Boolean> overridesFor(Map<String, Map<String, Boolean>> overrides,
                                                     String kind) {
        if (overrides == null) {
            return Map.of();
        }
        Map<String, Boolean> nested = overrides.get(kind);
        return nested == null ? Map.of() : nested;
    }

    public record CapabilityItem(String refId, String name, boolean enabled, String source) {
    }

    public record SessionCapabilitiesView(
            boolean locked,
            List<CapabilityItem> skills,
            List<CapabilityItem> mcpServers
    ) {
    }

    public record DisabledSets(Set<String> skills, Set<String> mcps) {
        public static DisabledSets empty() {
            return new DisabledSets(Set.of(), Set.of());
        }

        public boolean isSkillDisabled(String name) {
            return name != null && skills.contains(name.trim());
        }

        public boolean isMcpDisabled(String mcpId) {
            return mcpId != null && mcps.contains(mcpId.trim());
        }
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.trimToNull(sessionId);
    }

    /** 供前端 JSON 序列化的 Map 形状 */
    public Map<String, Object> toMap(SessionCapabilitiesView view) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("locked", view.locked());
        root.put("skills", view.skills().stream().map(this::itemMap).toList());
        root.put("mcpServers", view.mcpServers().stream().map(this::itemMap).toList());
        return root;
    }

    private Map<String, Object> itemMap(CapabilityItem item) {
        Map<String, Object> m = new HashMap<>();
        m.put("refId", item.refId());
        m.put("name", item.name());
        m.put("enabled", item.enabled());
        m.put("source", item.source());
        return m;
    }
}
