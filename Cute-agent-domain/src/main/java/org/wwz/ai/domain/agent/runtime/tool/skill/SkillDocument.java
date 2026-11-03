package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * skill_view 主文档加载结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDocument {

    /** 主文档元数据；加载成功后必然非 null（{@code DefaultSkillLoader} 在缺失时抛 {@link SkillLoadException}）。 */
    private SkillDescriptor descriptor;

    /** SKILL.md 正文；加载成功后必然非 null（解析器返回 null 时归一化为空串）。 */
    private String content;

    @Builder.Default
    private List<SkillFileDescriptor> linkedFiles = List.of();
}
