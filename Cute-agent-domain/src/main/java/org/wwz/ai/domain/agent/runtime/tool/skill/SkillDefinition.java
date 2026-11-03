package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    private String name;

    private String description;

    private Path basePath;

    private String content;

    @Builder.Default
    private Map<String, Object> frontMatter = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, SkillScriptDefinition> scripts = new LinkedHashMap<>();
}
