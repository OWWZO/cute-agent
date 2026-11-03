package org.wwz.ai.domain.agent.runtime.tool.skill;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Skill 注册中心
 */
public interface SkillRegistry {

    void refresh();

    /**
     * 带缓存的刷新：目录未变化且在 TTL 内时直接返回，不重扫。
     * 默认实现退化为 {@link #refresh()}。
     */
    default void refreshIfStale() {
        refresh();
    }

    /**
     * 当前目录快照的版本标识，仅在 {@link #refresh()} 改变目录内容后变化，供缓存与调试使用。
     */
    String version();

    boolean isEnabled();

    Collection<SkillDefinition> listSkills();

    Optional<SkillDefinition> findSkill(String skillName);

    SkillDefinition getRequiredSkill(String skillName);

    Path assertPathAllowed(Path candidatePath);

    String buildSkillDescription();
}
