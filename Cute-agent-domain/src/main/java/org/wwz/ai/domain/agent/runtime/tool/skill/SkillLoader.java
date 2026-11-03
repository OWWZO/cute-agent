package org.wwz.ai.domain.agent.runtime.tool.skill;

/**
 * 按需读取 SKILL.md 与包内附属文件。
 *
 * <p><b>安全契约（所有实现必须遵守）</b>：</p>
 * <ul>
 *   <li>{@link #loadFile} 的 {@code relativePath} 来自 Agent 工具输入，属于不可信数据；
 *       实现必须保证最终解析出的真实路径严格位于 skill 包根目录内。</li>
 *   <li>必须拒绝 {@code ..} 段、绝对路径、Windows 盘符/UNC 以及指向包外的符号链接；
 *       越界一律抛 {@link SkillLoadException}，不得返回包外内容。</li>
 *   <li>文件不存在时抛 {@link SkillFileNotFoundException}（附带可用文件清单），
 *       不得返回 {@code null}。</li>
 * </ul>
 * <p>默认实现见 {@code DefaultSkillLoader}（配合 {@link SkillPathGuard}）；
 * 新增实现（测试桩、DB 加载器、替代解析器）不得省略上述校验，
 * 否则该方法会退化为任意文件读取原语。</p>
 */
public interface SkillLoader {

    /**
     * 加载 skill 主文档（SKILL.md）。
     * <p>永不返回 null；{@code ref} 为 null 或 skill 未注册时抛 {@link SkillLoadException}。
     * 每次调用按需读取文件，不缓存正文。</p>
     */
    SkillDocument load(SkillRef ref);

    SkillFile loadFile(SkillRef ref, String relativePath);
}
