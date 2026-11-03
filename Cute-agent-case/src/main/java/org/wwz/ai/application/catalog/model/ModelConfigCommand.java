package org.wwz.ai.application.catalog.model;

/**
 * 模型配置管理写命令。
 * <p>当前实现用这些字段同时承载创建和更新：
 * <ul>
 *   <li>{@code create}：按 {@code modelId} 判断是否重复；{@code id} 可为 null。</li>
 *   <li>{@code updateById}：{@code id} 必须非 null。</li>
 *   <li>{@code updateByModelId}：{@code modelId} 必须非空白；{@code id} 非 null 时转走按 ID 更新。</li>
 * </ul>
 * boxed 数值字段可空，调用方应按对应服务路径的空值语义处理，不能直接假定已设置。
 */
public record ModelConfigCommand(
        Long id,
        String modelId,
        String apiId,
        String modelName,
        String modelType,
        String modelUsage,
        Integer supportsThinking,
        Integer contextWindow,
        Integer status) {
}
