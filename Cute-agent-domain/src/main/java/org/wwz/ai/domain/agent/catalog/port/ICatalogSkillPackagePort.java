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

    CatalogSkillPackageResult previewZip(byte[] zipBytes);

    CatalogSkillPackageResult installZip(byte[] zipBytes, String originalFilename, boolean replace);

    CatalogSkillPackageResult installFromMarkdown(String name, String description, String content, boolean replace);

    CatalogSkillPackageResult installFromUrl(String url, boolean replace);
}
