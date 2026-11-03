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
     * 目录是否启用。禁用时快照为空：{@link #list()} / {@link #search(SkillQuery)} 返回空集合，
     * {@link #find(String)} 返回 {@link Optional#empty()}，{@link #resolve(String)} /
     * {@link #getRequired(String)} 抛 {@link SkillLoadException}。
     */
    boolean isEnabled();

    List<SkillDescriptor> list();

    List<SkillSearchHit> search(SkillQuery query);

    /**
     * 解析 identifier 为 SkillRef；未找到时抛 {@link SkillLoadException}（不返回 null）。
     * <p>接受的写法与 {@link #find(String)} 一致：skill id、相对路径、{@code source:name} /
     * {@code source:relativePath}、唯一的裸 name。</p>
     */
    SkillRef resolve(String identifier);

    /**
     * 按 identifier 查找；null/空白或未找到返回 {@link Optional#empty()}。
     * <p>接受 skill id、相对路径、{@code source:name} / {@code source:relativePath}、唯一的裸 name；
     * 裸 name 命中多条时抛 {@link SkillLoadException}（歧义，需要更精确的写法）。</p>
     */
    Optional<SkillDescriptor> find(String identifier);

    /**
     * 与 {@link #find(String)} 相同的查找规则，但未找到时抛 {@link SkillLoadException}（不返回 null）。
     */
    SkillDescriptor getRequired(String identifier);
}
