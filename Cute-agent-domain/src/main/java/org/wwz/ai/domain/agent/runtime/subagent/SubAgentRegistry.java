package org.wwz.ai.domain.agent.runtime.subagent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 注册表：内置类型 + 可配置（DB）类型。
 * 内置 Agent 注册表，并支持运行时 replaceConfigured。
 */
@Component
public class SubAgentRegistry {

    public static final String TYPE_GENERAL_PURPOSE = "general-purpose";

    private final Map<String, SubAgentDefinition> builtins = new LinkedHashMap<>();
    /**
     * 可配置层整体快照：读完即发布新引用，读者永远看到完整的一代，
     * 不会在 clear + putAll 之间观察到空表或部分表。
     */
    private volatile Map<String, SubAgentDefinition> configured = Map.of();

    public SubAgentRegistry() {
        registerBuiltin(buildGeneralPurpose());
    }

    /**
     * 注册内置类型（启动时一次；同 key 覆盖）。
     */
    public void registerBuiltin(SubAgentDefinition definition) {
        putValidated(builtins, definition);
    }

    /**
     * 注册或覆盖任意类型（测试/动态扩展；configured 优先可见层仍由 resolve 合并规则决定）。
     * 若 key 与内置同名，写入 configured 可覆盖内置（除硬保护外，见 replaceConfigured）。
     */
    public void register(SubAgentDefinition definition) {
        // 读-改-写必须串行，否则与 replaceConfigured 并发时会丢失刚注册的类型。
        synchronized (this) {
            Map<String, SubAgentDefinition> next = new ConcurrentHashMap<>(configured);
            putValidated(next, definition);
            configured = next;
        }
    }

    /**
     * 用 DB 启用列表整体替换可配置层。
     * 保留全部内置；若 DB 条目 agentType 与内置同名则忽略该条（内置不可被配置覆盖）。
     */
    public void replaceConfigured(Collection<SubAgentDefinition> definitions) {
        Map<String, SubAgentDefinition> next = new ConcurrentHashMap<>();
        if (definitions != null) {
            for (SubAgentDefinition definition : definitions) {
                if (definition == null
                        || definition.getAgentType() == null
                        || definition.getAgentType().isBlank()) {
                    continue;
                }
                String key = definition.getAgentType().trim();
                if (builtins.containsKey(key)) {
                    continue;
                }
                next.put(key, normalize(definition));
            }
        }
        synchronized (this) {
            configured = next;
        }
    }

    public Optional<SubAgentDefinition> find(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return Optional.empty();
        }
        String key = agentType.trim();
        Map<String, SubAgentDefinition> snapshot = configured;
        SubAgentDefinition configuredDef = snapshot.get(key);
        if (configuredDef != null) {
            return Optional.of(configuredDef);
        }
        return Optional.ofNullable(builtins.get(key));
    }

    public SubAgentDefinition require(String agentType) {
        return find(agentType).orElseThrow(() -> new IllegalArgumentException(
                "未知 subagent_type: " + agentType + "。可用类型: " + String.join(", ", listTypeNames())));
    }

    public SubAgentDefinition resolveOrDefault(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return require(TYPE_GENERAL_PURPOSE);
        }
        return require(agentType);
    }

    /**
     * 合并列表：内置在前，可配置在后（同 key 已在 replace 时去重）。
     */
    public Collection<SubAgentDefinition> list() {
        Map<String, SubAgentDefinition> snapshot = configured;
        List<SubAgentDefinition> all = new ArrayList<>(builtins.size() + snapshot.size());
        all.addAll(builtins.values());
        all.addAll(snapshot.values());
        return all;
    }

    public List<String> listTypeNames() {
        Map<String, SubAgentDefinition> snapshot = configured;
        List<String> names = new ArrayList<>();
        names.addAll(builtins.keySet());
        names.addAll(snapshot.keySet());
        return names;
    }

    public int configuredCount() {
        return configured.size();
    }

    private static void putValidated(Map<String, SubAgentDefinition> target, SubAgentDefinition definition) {
        if (definition == null || definition.getAgentType() == null || definition.getAgentType().isBlank()) {
            throw new IllegalArgumentException("SubAgentDefinition.agentType 不能为空");
        }
        target.put(definition.getAgentType().trim(), normalize(definition));
    }

    private static SubAgentDefinition normalize(SubAgentDefinition definition) {
        return SubAgentDefinition.builder()
                .agentType(definition.getAgentType().trim())
                .whenToUse(definition.getWhenToUse())
                .systemPrompt(definition.getSystemPrompt())
                .allowedTools(definition.getAllowedTools())
                .disallowedTools(definition.getDisallowedTools() == null
                        ? Set.of()
                        : definition.getDisallowedTools())
                .toolPolicyMode(definition.getToolPolicyMode())
                .deferredTools(definition.getDeferredTools() == null
                        ? Set.of()
                        : definition.getDeferredTools())
                .maxSteps(definition.getMaxSteps())
                .build();
    }

    private static SubAgentDefinition buildGeneralPurpose() {
        return SubAgentDefinition.builder()
                .agentType(TYPE_GENERAL_PURPOSE)
                .whenToUse("通用多步骤任务：检索、分析、代码、办公与报告交付；需要较全工具或不确定类型时使用。")
                .systemPrompt("""
                        通用执行补充：
                        - 一切以本任务 prompt 为准；看不到用户与协调者的完整对话。
                        - 根据任务需要选择检索、分析、代码、办公或文档工具。
                        - 不确定完成标准时，优先产出可被后续 Agent 发现的工作区报告，并在结论文本中给出路径。
                        """)
                .allowedTools(Set.of("*"))
                .disallowedTools(Set.of())
                .toolPolicyMode(SubAgentDefinition.TOOL_POLICY_INHERIT)
                .deferredTools(Set.of())
                .maxSteps(200)
                .build();
    }
}
