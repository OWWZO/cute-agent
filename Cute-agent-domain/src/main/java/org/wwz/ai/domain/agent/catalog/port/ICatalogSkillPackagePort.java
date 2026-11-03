package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.CatalogSkillPackageResult;

/**
 * 技能包读写端口。
 *
 * <p>安全契约（实现方必须满足，不能只依赖调用方）：</p>
 * <ul>
 *   <li>{@link #previewZip(byte[])} / {@link #installZip(byte[], String, boolean)} 的
 *       {@code zipBytes} 是不可信输入，必须非 {@code null} 且为合法 ZIP；</li>
 *   <li>解包时必须规范化 entry 路径，拒绝任何经 {@code ../}、绝对路径或符号链接逃逸目标目录的条目
 *       （zip-slip）；</li>
 *   <li>必须限制 entry 数量与解压后总大小，避免压缩炸弹；</li>
 *   <li>{@link #installFromUrl(String, boolean)} 只允许 {@code http}/{@code https}，禁止带凭据的 URL；
 *       必须解析真实 IP 并拒绝 loopback / link-local / private / ULA / multicast / 未指定地址
 *       （含 IPv4-mapped 等非规范写法），重定向目标必须逐跳复检；必须设置连接/读取超时，
 *       并在缓冲前限制响应体大小。部署若有允许的 host 后缀，应优先使用白名单。</li>
 * </ul>
 */
public interface ICatalogSkillPackagePort {

    /**
     * 预览技能包内容，不落盘。
     * <p>失败语义：所有失败都以 {@code SkillLoadException}（unchecked）抛出，返回的结果一定代表成功。</p>
     *
     * @param zipBytes 不可信输入，必须非 null 且为合法 ZIP
     * @return 技能名、描述、正文预览与同名冲突标记
     */
    CatalogSkillPackageResult previewZip(byte[] zipBytes);

    /**
     * 从 ZIP 安装技能。
     * <p>失败语义：所有失败都以 {@code SkillLoadException}（unchecked）抛出，返回的结果一定代表成功。</p>
     * <p>{@code replace} 语义：技能已存在且 {@code replace=false} 时抛异常；{@code replace=true} 时
     * 删除整个同名技能目录后重建，目录内旧文件不会保留。</p>
     *
     * @param zipBytes         不可信输入，必须非 null 且为合法 ZIP
     * @param originalFilename 仅用于日志，可为 null；不参与技能命名或路径解析
     * @param replace          是否覆盖同名技能
     */
    CatalogSkillPackageResult installZip(byte[] zipBytes, String originalFilename, boolean replace);

    /**
     * 从粘贴的 SKILL.md 正文安装技能。
     * <p>失败语义：所有失败都以 {@code SkillLoadException}（unchecked）抛出，返回的结果一定代表成功。</p>
     * <p>{@code replace} 语义：技能已存在且 {@code replace=false} 时抛异常；{@code replace=true} 时
     * 只覆盖 SKILL.md，保留目录内已有的 scripts/references 等文件（与 {@link #installZip} 的整体重建不同）。</p>
     *
     * @param name        技能名；为空时回退到正文 frontmatter 的 {@code name}
     * @param description 描述；为空时回退到正文 frontmatter 的 {@code description}
     * @param content     SKILL.md 正文（可含 frontmatter）；正文为空时抛异常
     * @param replace     是否覆盖同名技能
     */
    CatalogSkillPackageResult installFromMarkdown(String name, String description, String content, boolean replace);

    /**
     * 从 URL 下载并安装技能包。
     * <p>失败语义：所有失败（含下载失败、内容既非 ZIP 也非 SKILL.md）都以 {@code SkillLoadException}
     * （unchecked）抛出，返回的结果一定代表成功。</p>
     * <p>{@code replace} 语义：下载到 ZIP 时按 {@link #installZip} 处理（整体重建）；
     * 下载到 SKILL.md 文本时按 {@link #installFromMarkdown} 处理（只覆盖 SKILL.md）。</p>
     *
     * @param url     只允许 {@code http}/{@code https}
     * @param replace 是否覆盖同名技能
     */
    CatalogSkillPackageResult installFromUrl(String url, boolean replace);
}
