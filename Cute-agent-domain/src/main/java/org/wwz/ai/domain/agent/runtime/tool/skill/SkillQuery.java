package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Skill 目录检索条件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillQuery {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 25;

    private String text;

    private String category;

    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    private String source;

    private Integer limit;

    @Builder.Default
    private Set<String> disabledNames = new LinkedHashSet<>();

    /**
     * 返回不可变副本，避免调用方通过 getter 反向修改检索条件。
     */
    public Set<String> getTags() {
        return tags == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(tags));
    }

    /**
     * 返回不可变副本，避免调用方通过 getter 反向修改检索条件。
     */
    public Set<String> getDisabledNames() {
        return disabledNames == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(disabledNames));
    }

    public int resolveLimit() {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
