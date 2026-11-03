package org.wwz.ai.domain.agent.catalog.model;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 技能与 MCP 能力目录。
 * <p>构建后不可再修改：列表由构造器与 getter 做防御性拷贝。</p>
 */
@Getter
@Builder
public class CatalogCapabilities {

    private final List<CatalogSkill> skills;
    private final List<CatalogMcp> mcps;

    private CatalogCapabilities(List<CatalogSkill> skills, List<CatalogMcp> mcps) {
        this.skills = immutableCopy(skills);
        this.mcps = immutableCopy(mcps);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
