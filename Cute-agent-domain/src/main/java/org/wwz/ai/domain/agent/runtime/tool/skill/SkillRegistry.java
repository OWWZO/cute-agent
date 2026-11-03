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

    /**
     * 校验路径并返回 guard 规范化后的安全路径。
     * <p>返回值必须用于后续文件 I/O；调用方若继续使用原始 {@code candidatePath}，
     * 符号链接 / {@code ..} 解析结果会被绕过。</p>
     */
    Path resolveAllowedPath(Path candidatePath);

    /**
     * 仅断言路径合法，不返回安全路径。
     * <p>校验后还要读写文件时请改用 {@link #resolveAllowedPath(Path)} 并使用其返回值。</p>
     */
    default void assertPathAllowed(Path candidatePath) {
        resolveAllowedPath(candidatePath);
    }

    String buildSkillDescription();
}
