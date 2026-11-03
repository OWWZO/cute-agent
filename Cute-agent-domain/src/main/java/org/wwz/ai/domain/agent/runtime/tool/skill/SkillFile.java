package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * skill_view(file_path) 加载结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFile {

    private String name;

    private String path;

    /**
     * 整个 skill 文件正文，可能达到数 MB。排除出 toString/equals：
     * 误日志或错误信息不会把整篇正文吐出来，{@link #size} 仍可作为可观测线索。
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String content;

    private boolean binary;

    private long size;
}
