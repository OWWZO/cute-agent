package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * skill_view(file_path) 加载结果。
 * <p>{@code binary=false} 时 {@link #content} 是 UTF-8 解码后的全文（超过单次注入上限时只含开头，
 * {@link #truncated}=true）；{@code binary=true} 时 {@code content} 只是人类可读占位摘要
 * （如 {@code "[Binary file: name, size: N bytes]"}），不是原始字节。{@link #size} 始终是文件字节数。</p>
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

    /** 文件超过单次注入上限，{@link #content} 只包含开头部分。 */
    private boolean truncated;
}
