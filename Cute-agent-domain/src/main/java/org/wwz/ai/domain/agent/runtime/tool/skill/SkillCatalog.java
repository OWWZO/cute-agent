package org.wwz.ai.domain.agent.runtime.tool.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 元数据只读目录。调用方只搜索和解析，不感知扫描与缓存。
 * <p>刷新、版本号、路径校验等管理操作属于写侧 {@link SkillRegistry}，本接口不暴露。</p>
 * <p>并发契约：{@link #list()}、{@link #search(SkillQuery)}、{@link #resolve(String)}、
 * {@link #find(String)}、{@link #getRequired(String)} 的每次调用都必须读取自一次已完整构建的快照，
 * 不得暴露刷新过程中的半成品；跨调用不保证观察到同一版本。</p>
 */
public interface SkillCatalog {

    /**
     * 目录是否启用；禁用时调用方应视为空目录。
     */
    boolean isEnabled();

    List<SkillDescriptor> list();

    List<SkillSearchHit> search(SkillQuery query);

    SkillRef resolve(String identifier);

    Optional<SkillDescriptor> find(String identifier);

    SkillDescriptor getRequired(String identifier);
}
