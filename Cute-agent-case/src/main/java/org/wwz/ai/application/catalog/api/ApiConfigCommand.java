package org.wwz.ai.application.catalog.api;

/**
 * API 配置管理写命令。
 * <p>同一类型用于创建、按主键更新和按业务 ID 更新；各入口的当前要求见组件说明。
 */
public record ApiConfigCommand(
        /** 主键。{@code updateById} 必须非 null；创建时可为 null；{@code updateByApiId} 不以该值定位记录。 */
        Long id,

        /** API 业务标识。{@code updateByApiId} 必须非空白；创建时应由调用方提供唯一值。 */
        String apiId,

        String baseUrl,
        String apiKey,
        String completionsPath,
        String embeddingsPath,

        /**
         * 状态：{@code 1} 表示启用，{@code 0} 表示停用。
         * <p>null 表示调用方未提供；创建和更新入口当前不会在本命令层统一校验该值。</p>
         */
        Integer status) {

    @Override
    public String toString() {
        return "ApiConfigCommand[id=" + id
                + ", apiId=" + apiId
                + ", baseUrl=" + baseUrl
                + ", apiKey=<redacted>"
                + ", completionsPath=" + completionsPath
                + ", embeddingsPath=" + embeddingsPath
                + ", status=" + status + "]";
    }
}
